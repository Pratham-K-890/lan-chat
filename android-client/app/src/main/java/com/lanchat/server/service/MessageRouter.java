package com.lanchat.server.service;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.lanchat.common.model.Group;
import com.lanchat.common.protocol.Envelope;
import com.lanchat.common.protocol.MessageType;
import com.lanchat.common.util.JsonUtil;
import com.lanchat.server.ChatServer;
import com.lanchat.server.ClientHandler;
import com.lanchat.server.db.Database;
import com.lanchat.server.service.PresenceTracker;

/**
 * Routes messages between clients AND persists them to the SQLite `messages` table.
 *
 * Persistence rules:
 *   BROADCAST   → channel "broadcast"
 *   PRIVATE_MSG → channel "dm:<user1>:<user2>"  (alphabetically sorted)
 *   GROUP_MSG   → channel "group:<groupName>"
 *
 * The content stored is the AES-encrypted text — the server never sees plaintext.
 * History is capped at 200 messages per channel (enforced by Database.insertMessage).
 */
public final class MessageRouter {

    private MessageRouter() {}

    // ── Broadcast ────────────────────────────────────────────────────

    public static void broadcast(ClientHandler sender, Envelope env) {
        if (sender.getUsername() == null) return;

        ObjectNode payload = (ObjectNode) env.payload;
        payload.put("from",      sender.getUsername());
        payload.put("timestamp", System.currentTimeMillis());

        // Persist encrypted content to messages table
        String content   = payload.has("text") ? payload.get("text").asText() : "";
        long   timestamp = payload.get("timestamp").asLong();
        Database.insertMessage("broadcast", sender.getUsername(), content, timestamp);

        Envelope out = new Envelope(MessageType.BROADCAST, payload);
        ChatServer.onlineUsers.values().forEach(h -> h.send(out));
    }

    // ── Private message ───────────────────────────────────────────────

    public static void sendPrivate(ClientHandler sender, Envelope env) {
        if (sender.getUsername() == null) return;

        String targetUsername = env.payload.get("to").asText();
        ClientHandler target  = PresenceTracker.get(targetUsername);

        if (target == null) {
            sender.sendError("USER_NOT_FOUND", "User '" + targetUsername + "' is not online");
            return;
        }

        ObjectNode payload   = (ObjectNode) env.payload;
        payload.put("from",       sender.getUsername());
        payload.put("timestamp",  System.currentTimeMillis());

        // Persist to DB (channel is alphabetically sorted dm:user1:user2)
        String  channel   = Database.dmChannel(sender.getUsername(), targetUsername);
        String  content   = payload.has("text") ? payload.get("text").asText() : "";
        long    timestamp = payload.get("timestamp").asLong();
        Database.insertMessage(channel, sender.getUsername(), content, timestamp);

        Envelope pm = new Envelope(MessageType.PRIVATE_MSG, payload);
        target.send(pm);
        sender.send(pm);   // echo back to sender
    }

    // ── Group message ─────────────────────────────────────────────────

    public static void sendToGroup(ClientHandler sender, Envelope env) {
        if (sender.getUsername() == null) return;

        String groupName = env.payload.get("groupName").asText();
        Group  group     = GroupService.getGroup(groupName);

        if (group == null) {
            sender.sendError("GROUP_NOT_FOUND", "Group '" + groupName + "' does not exist");
            return;
        }
        if (!group.isMember(sender.getUsername())) {
            sender.sendError("NOT_MEMBER", "You are not a member of group '" + groupName + "'");
            return;
        }

        ObjectNode payload = (ObjectNode) env.payload;
        payload.put("from",      sender.getUsername());
        payload.put("timestamp", System.currentTimeMillis());

        // Persist to DB
        String channel   = Database.groupChannel(groupName);
        String content   = payload.has("text") ? payload.get("text").asText() : "";
        long   timestamp = payload.get("timestamp").asLong();
        Database.insertMessage(channel, sender.getUsername(), content, timestamp);

        Envelope groupMsg = new Envelope(MessageType.GROUP_MSG, payload);
        group.getMembers().forEach(member -> {
            ClientHandler h = PresenceTracker.get(member);
            if (h != null) h.send(groupMsg);
        });
    }

    // ── System notifications (not persisted — transient join/leave msgs) ──

    public static void broadcastSystemMessage(String text, ClientHandler except) {
        ObjectNode payload = JsonUtil.buildPayload(
                "from",      "SYSTEM",
                "text",      text,
                "timestamp", System.currentTimeMillis());
        Envelope env = new Envelope(MessageType.BROADCAST, payload);
        ChatServer.onlineUsers.values().stream()
                .filter(h -> h != except)
                .forEach(h -> h.send(env));
    }

    public static void broadcastToGroup(Group group, String text, ClientHandler except) {
        ObjectNode payload = JsonUtil.buildPayload(
                "from",      "SYSTEM",
                "text",      text,
                "timestamp", System.currentTimeMillis());
        Envelope env = new Envelope(MessageType.BROADCAST, payload);
        group.getMembers().forEach(member -> {
            ClientHandler h = PresenceTracker.get(member);
            if (h != null && h != except) h.send(env);
        });
    }
}
