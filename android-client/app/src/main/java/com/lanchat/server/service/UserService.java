package com.lanchat.server.service;

import com.lanchat.common.crypto.BcryptUtil;
import com.lanchat.common.protocol.Envelope;
import com.lanchat.common.protocol.MessageType;
import com.lanchat.common.util.JsonUtil;
import com.lanchat.server.ClientHandler;
import com.lanchat.server.ChatServer;
import com.lanchat.server.service.PresenceTracker;
import com.lanchat.server.service.MessageRouter;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Authentication service — in-memory store for Android embedded server.
 *
 * On the Android host phone, users are stored in a ConcurrentHashMap
 * (no SQLite — the Database class is a stub on Android).
 * Users persist for the lifetime of the server session only.
 */
public final class UserService {

    // username → bcrypt hash (in-memory, reset when server stops)
    private static final ConcurrentHashMap<String, String> userStore
            = new ConcurrentHashMap<>();

    // username → session token
    private static final ConcurrentHashMap<String, String> sessions
            = new ConcurrentHashMap<>();

    private UserService() {}

    // ── Clear state when server restarts ─────────────────────────────

    public static void clearAll() {
        userStore.clear();
        sessions.clear();
    }

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
        if (userStore.containsKey(username)) {
            // Username taken — try to login instead
            handleLogin(client, env);
            return;
        }

        String hash = BcryptUtil.hash(password);
        userStore.put(username, hash);
        System.out.println("[AUTH] Registered: " + username);
        completeLogin(client, username);
    }

    // ── Login ─────────────────────────────────────────────────────────

    public static void handleLogin(ClientHandler client, Envelope env) {
        String username = env.payload.get("username").asText();
        String password = env.payload.get("password").asText();
        String stored   = userStore.get(username);

        if (stored == null) {
            // User doesn't exist yet — auto-register them
            handleRegister(client, env);
            return;
        }

        if (!BcryptUtil.verify(password, stored)) {
            try { Thread.sleep(500); } catch (InterruptedException ignored) {}
            client.send(JsonUtil.envelope(MessageType.AUTH_FAILURE,
                    "reason", "Wrong password"));
            return;
        }

        if (PresenceTracker.isOnline(username)) {
            client.sendError("ALREADY_ONLINE", "Already logged in on another device");
            return;
        }

        completeLogin(client, username);
    }

    // ── Shared post-auth ──────────────────────────────────────────────

    private static void completeLogin(ClientHandler client, String username) {
        String token = UUID.randomUUID().toString();
        sessions.put(username, token);
        client.setUsername(username);
        PresenceTracker.register(username, client);

        // 1. Tell client they're authenticated
        client.send(JsonUtil.envelope(MessageType.AUTH_SUCCESS,
                "username", username, "token", token));

        // 2. Send online user list
        List<String> online = PresenceTracker.getOnlineUsernames();
        client.send(new Envelope(MessageType.USER_LIST,
                JsonUtil.buildPayloadWithList("users", online)));

        // 3. Broadcast that this user joined
        MessageRouter.broadcastSystemMessage(username + " joined the chat", client);
        var joinPayload = JsonUtil.buildPayload("username", username);
        var joinEnv = new Envelope(MessageType.USER_JOINED, joinPayload);
        ChatServer.onlineUsers.values().stream()
                .filter(h -> !h.getUsername().equals(username))
                .forEach(h -> h.send(joinEnv));

        System.out.println("[AUTH] " + username + " logged in. Online: "
                + PresenceTracker.count());
    }

    // ── History request ────────────────────────────────────────────────

    public static void handleHistoryRequest(ClientHandler client, Envelope env) {
        // In-memory server has no history — send empty response
        String channel = env.payload.has("channel")
                ? env.payload.get("channel").asText() : "broadcast";
        var mapper  = JsonUtil.mapper();
        var payload = mapper.createObjectNode();
        payload.put("channel", channel);
        payload.set("messages", mapper.createArrayNode());
        client.send(new Envelope(MessageType.HISTORY_RESPONSE, payload));
    }

    public static boolean validateToken(String username, String token) {
        return token != null && token.equals(sessions.get(username));
    }

    public static void removeSession(String username) {
        sessions.remove(username);
    }
}
