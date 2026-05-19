package com.lanchat.server.db;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Android-side stub for Database.
 * The real SQLite implementation runs on the server (PC/Termux) only.
 * This stub allows the embedded server classes to compile inside the APK.
 */
public final class Database {

    private Database() {}

    public static void init() {}
    public static void close() {}
    public static void printStats() {}

    // ── Users ─────────────────────────────────────────────────────────
    public static boolean insertUser(String username, String hash) { return true; }
    public static String  getUserHash(String username) { return null; }
    public static boolean userExists(String username)  { return false; }
    public static List<String> getAllUsernames()        { return new ArrayList<>(); }

    // ── Groups ────────────────────────────────────────────────────────
    public static boolean insertGroup(String groupName, String createdBy) { return true; }
    public static boolean groupExists(String groupName) { return false; }
    public static Map<String, String> getAllGroups()    { return new LinkedHashMap<>(); }

    // ── Group members ─────────────────────────────────────────────────
    public static void addGroupMember(String groupName, String username) {}
    public static void removeGroupMember(String groupName, String username) {}
    public static List<String> getGroupMembers(String groupName) { return new ArrayList<>(); }
    public static List<String> getUserGroups(String username)    { return new ArrayList<>(); }

    // ── Messages ──────────────────────────────────────────────────────
    public static void insertMessage(String channel, String sender,
                                     String content, long timestamp) {}
    public static List<MessageRow> getHistory(String channel, int limit) {
        return new ArrayList<>();
    }
    public static String dmChannel(String user1, String user2) {
        return "dm:" + (user1.compareTo(user2) <= 0
                ? user1 + ":" + user2
                : user2 + ":" + user1);
    }
    public static String groupChannel(String groupName) {
        return "group:" + groupName;
    }

    // ── MessageRow — plain class instead of record (record needs Java 16+) ──
    public static final class MessageRow {
        public final long   id;
        public final String sender;
        public final String content;
        public final long   timestamp;

        public MessageRow(long id, String sender, String content, long timestamp) {
            this.id        = id;
            this.sender    = sender;
            this.content   = content;
            this.timestamp = timestamp;
        }

        public long   id()        { return id; }
        public String sender()    { return sender; }
        public String content()   { return content; }
        public long   timestamp() { return timestamp; }
    }
}
