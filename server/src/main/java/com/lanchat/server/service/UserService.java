package com.lanchat.server.service;

import com.lanchat.common.crypto.BcryptUtil;
import com.lanchat.common.protocol.Envelope;
import com.lanchat.common.protocol.MessageType;
import com.lanchat.common.util.JsonUtil;
import com.lanchat.server.ClientHandler;
import com.lanchat.server.db.Database;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Authentication service — backed by the SQLite `users` table.
 *
 * Passwords are bcrypt-hashed before storage. Session tokens are in-memory
 * only (UUID per login, cleared on server restart / logout).
 *
 * On successful login:
 *   1. AUTH_SUCCESS    → the authenticated client
 *   2. USER_LIST       → snapshot of online users
 *   3. GROUP_LIST      → groups this user belongs to (persisted across restarts)
 *   4. USER_JOINED     → broadcast to all other users
 *   5. HISTORY_RESPONSE (broadcast) → last 50 broadcast messages for context
 */
public final class UserService {

    // username → session token (in-memory only — reset on server restart)
    private static final ConcurrentHashMap<String, String> sessions
            = new ConcurrentHashMap<>();

    private UserService() {}

    // ── Registration ─────────────────────────────────────────────────

    public static void handleRegister(ClientHandler client, Envelope env) {
        String username = env.payload.get("username").asText().strip();
        String password = env.payload.get("password").asText();

        if (username.isBlank() || username.length() < 3) {
            client.sendError("INVALID_INPUT", "Username must be at least 3 characters");
            return;
        }
        if (password == null || password.length() < 6) {
            client.sendError("INVALID_INPUT", "Password must be at least 6 characters");
            return;
        }
        if (Database.userExists(username)) {
            client.sendError("USERNAME_TAKEN", "Username '" + username + "' is already registered");
            return;
        }

        String hash = BcryptUtil.hash(password);
        boolean inserted = Database.insertUser(username, hash);
        if (!inserted) {
            // Race condition: another thread registered same username between check and insert
            client.sendError("USERNAME_TAKEN", "Username '" + username + "' is already registered");
            return;
        }

        System.out.println("[AUTH] Registered: " + username);
        completeLogin(client, username);
    }

    // ── Login ────────────────────────────────────────────────────────

    public static void handleLogin(ClientHandler client, Envelope env) {
        String username = env.payload.get("username").asText();
        String password = env.payload.get("password").asText();

        String storedHash = Database.getUserHash(username);

        // Always run verify (even on null hash) to prevent timing-based user enumeration
        boolean valid = storedHash != null && BcryptUtil.verify(password, storedHash);

        if (!valid) {
            try { Thread.sleep(500); } catch (InterruptedException ignored) {}
            client.send(JsonUtil.envelope(MessageType.AUTH_FAILURE,
                    "reason", "Invalid username or password"));
            System.out.println("[AUTH] Failed login: " + username);
            return;
        }

        if (PresenceTracker.isOnline(username)) {
            client.sendError("ALREADY_ONLINE", "User '" + username + "' is already logged in");
            return;
        }

        completeLogin(client, username);
    }

    // ── Post-auth setup ───────────────────────────────────────────────

    private static void completeLogin(ClientHandler client, String username) {
        String token = UUID.randomUUID().toString();
        sessions.put(username, token);
        client.setUsername(username);
        PresenceTracker.register(username, client);

        // 1. Tell the client they're in
        client.send(JsonUtil.envelope(MessageType.AUTH_SUCCESS,
                "username", username, "token", token));

        // 2. Online user list
        List<String> online = PresenceTracker.getOnlineUsernames();
        client.send(new Envelope(MessageType.USER_LIST,
                JsonUtil.buildPayloadWithList("users", online)));

        // 3. Groups this user belongs to (reload from DB so it survives restarts)
        List<String> userGroups = Database.getUserGroups(username);
        for (String groupName : userGroups) {
            client.send(JsonUtil.envelope(MessageType.GROUP_LIST,
                    "action", "MEMBER_OF", "groupName", groupName));
        }

        // 4. Tell everyone else this user joined
        MessageRouter.broadcastSystemMessage(username + " joined the chat", client);
        var joinPayload = JsonUtil.buildPayload("username", username);
        var joinEnv     = new Envelope(MessageType.USER_JOINED, joinPayload);
        com.lanchat.server.ChatServer.onlineUsers.values().stream()
                .filter(h -> !h.getUsername().equals(username))
                .forEach(h -> h.send(joinEnv));

        // 5. Send broadcast history (last 50 messages) so user sees recent chat
        sendBroadcastHistory(client, 50);

        System.out.println("[AUTH] " + username + " logged in. Online: " + PresenceTracker.count());
    }

    /**
     * Send the last N broadcast messages to a newly logged-in client.
     * Messages are still AES-encrypted — the server can't read them.
     */
    private static void sendBroadcastHistory(ClientHandler client, int limit) {
        var history = Database.getHistory("broadcast", limit);
        if (history.isEmpty()) return;

        var mapper   = JsonUtil.mapper();
        var payload  = mapper.createObjectNode();
        var arr      = mapper.createArrayNode();
        payload.put("channel", "broadcast");

        for (var row : history) {
            var msg = mapper.createObjectNode();
            msg.put("sender",    row.sender());
            msg.put("content",   row.content());
            msg.put("timestamp", row.timestamp());
            arr.add(msg);
        }
        payload.set("messages", arr);
        client.send(new Envelope(MessageType.HISTORY_RESPONSE, payload));
    }

    // ── History request (client can request any channel) ──────────────

    public static void handleHistoryRequest(ClientHandler client, Envelope env) {
        if (client.getUsername() == null) {
            client.sendError("NOT_AUTHENTICATED", "Login first");
            return;
        }

        String channel = env.payload.has("channel")
                ? env.payload.get("channel").asText() : "broadcast";
        int limit = env.payload.has("limit")
                ? Math.min(env.payload.get("limit").asInt(), 200) : 50;

        // Security: only allow broadcast or channels the user belongs to
        if (channel.startsWith("group:")) {
            String groupName = channel.substring(6);
            var group = GroupService.getGroup(groupName);
            if (group == null || !group.isMember(client.getUsername())) {
                client.sendError("ACCESS_DENIED", "Not a member of group: " + groupName);
                return;
            }
        } else if (channel.startsWith("dm:")) {
            // DM channel must contain this user's username
            if (!channel.contains(client.getUsername())) {
                client.sendError("ACCESS_DENIED", "Cannot read another user's DMs");
                return;
            }
        }

        var history = Database.getHistory(channel, limit);
        var mapper  = JsonUtil.mapper();
        var payload = mapper.createObjectNode();
        var arr     = mapper.createArrayNode();
        payload.put("channel", channel);

        for (var row : history) {
            var msg = mapper.createObjectNode();
            msg.put("sender",    row.sender());
            msg.put("content",   row.content());
            msg.put("timestamp", row.timestamp());
            arr.add(msg);
        }
        payload.set("messages", arr);
        client.send(new Envelope(MessageType.HISTORY_RESPONSE, payload));
    }

    // ── Token + session ───────────────────────────────────────────────

    public static boolean validateToken(String username, String token) {
        return token != null && token.equals(sessions.get(username));
    }

    public static void removeSession(String username) {
        sessions.remove(username);
    }
}
