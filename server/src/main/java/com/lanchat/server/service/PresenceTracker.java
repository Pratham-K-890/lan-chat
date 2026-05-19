package com.lanchat.server.service;

import com.lanchat.server.ChatServer;
import com.lanchat.server.ClientHandler;

import java.util.ArrayList;
import java.util.List;

/**
 * Wraps ChatServer.onlineUsers with higher-level presence operations.
 *
 * All methods are effectively thread-safe because ConcurrentHashMap
 * is the backing store — no explicit synchronization needed here.
 */
public final class PresenceTracker {

    private PresenceTracker() {}

    /** Register a newly authenticated user. */
    public static void register(String username, ClientHandler handler) {
        ChatServer.onlineUsers.put(username, handler);
    }

    /** Remove a user on disconnect. */
    public static void remove(String username) {
        ChatServer.onlineUsers.remove(username);
    }

    /** Check whether a username is currently connected. */
    public static boolean isOnline(String username) {
        return ChatServer.onlineUsers.containsKey(username);
    }

    /** Get the handler for a username, or null if offline. */
    public static ClientHandler get(String username) {
        return ChatServer.onlineUsers.get(username);
    }

    /**
     * Snapshot of all online usernames.
     * Safe to iterate even if the underlying map changes during the call.
     */
    public static List<String> getOnlineUsernames() {
        return new ArrayList<>(ChatServer.onlineUsers.keySet());
    }

    /** Total number of connected clients. */
    public static int count() {
        return ChatServer.onlineUsers.size();
    }
}
