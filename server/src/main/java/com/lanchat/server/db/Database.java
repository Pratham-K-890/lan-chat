package com.lanchat.server.db;

import java.sql.*;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Central SQLite database for LAN Chat.
 *
 * File: lanchat.db  — created automatically next to the server JAR on first run.
 * No separate database server, no configuration, no installation required.
 *
 * Schema (four tables):
 * ┌──────────────────┬───────────────────────────────────┬──────────────────┐
 * │ Table            │ Stores                            │ Survives restart │
 * ├──────────────────┼───────────────────────────────────┼──────────────────┤
 * │ users            │ username + bcrypt hash            │ ✅ Yes           │
 * │ groups           │ group name + who created it       │ ✅ Yes           │
 * │ group_members    │ who belongs to which group        │ ✅ Yes           │
 * │ messages         │ encrypted chat history (last 200) │ ✅ Yes           │
 * └──────────────────┴───────────────────────────────────┴──────────────────┘
 *
 * Thread safety: all public methods are synchronized on the Connection monitor.
 * SQLite itself is single-writer; the synchronized keyword ensures we never
 * have two threads writing simultaneously.
 *
 * Android / Termux: SQLite JDBC is bundled in the fat JAR — works everywhere.
 */
public final class Database {

    /** Path to the database file. Relative to the working directory (next to the JAR). */
    private static final String DB_PATH = "lanchat.db";
    private static final int    MAX_MESSAGES = 200;  // per-channel history cap

    private static Connection conn;

    private Database() {}

    // ── Initialisation ────────────────────────────────────────────────

    /**
     * Open the database and create all tables if they don't exist.
     * Call once at server startup — before any service tries to use the DB.
     */
    public static synchronized void init() {
        try {
            // Load the SQLite JDBC driver explicitly (needed in fat JARs)
            Class.forName("org.sqlite.JDBC");

            conn = DriverManager.getConnection("jdbc:sqlite:" + DB_PATH);

            // WAL mode: much better concurrent read performance
            exec("PRAGMA journal_mode=WAL");
            // Enforce foreign key constraints
            exec("PRAGMA foreign_keys=ON");

            createTables();
            System.out.println("[DB] lanchat.db opened. Tables ready.");

        } catch (Exception e) {
            throw new RuntimeException("Failed to initialise database: " + e.getMessage(), e);
        }
    }

    private static void createTables() throws SQLException {

        // ── users ─────────────────────────────────────────────────────
        exec("""
            CREATE TABLE IF NOT EXISTS users (
                username    TEXT PRIMARY KEY NOT NULL,
                hash        TEXT NOT NULL,
                created_at  INTEGER NOT NULL DEFAULT (strftime('%s','now'))
            )
            """);

        // ── groups ────────────────────────────────────────────────────
        exec("""
            CREATE TABLE IF NOT EXISTS groups (
                group_name  TEXT PRIMARY KEY NOT NULL,
                created_by  TEXT NOT NULL,
                created_at  INTEGER NOT NULL DEFAULT (strftime('%s','now'))
            )
            """);

        // ── group_members ─────────────────────────────────────────────
        exec("""
            CREATE TABLE IF NOT EXISTS group_members (
                group_name  TEXT NOT NULL,
                username    TEXT NOT NULL,
                joined_at   INTEGER NOT NULL DEFAULT (strftime('%s','now')),
                PRIMARY KEY (group_name, username)
            )
            """);

        // ── messages ──────────────────────────────────────────────────
        // channel: "broadcast", "dm:<user1>:<user2>" (sorted), or "group:<name>"
        exec("""
            CREATE TABLE IF NOT EXISTS messages (
                id          INTEGER PRIMARY KEY AUTOINCREMENT,
                channel     TEXT    NOT NULL,
                sender      TEXT    NOT NULL,
                content     TEXT    NOT NULL,
                timestamp   INTEGER NOT NULL DEFAULT (strftime('%s','now') * 1000)
            )
            """);

        // Index for fast channel history queries
        exec("CREATE INDEX IF NOT EXISTS idx_messages_channel ON messages(channel, id DESC)");
    }

    // ── Close ─────────────────────────────────────────────────────────

    public static synchronized void close() {
        try {
            if (conn != null && !conn.isClosed()) {
                conn.close();
                System.out.println("[DB] Database closed.");
            }
        } catch (SQLException e) {
            System.err.println("[DB] Close error: " + e.getMessage());
        }
    }

    // ════════════════════════════════════════════════════════════════
    // USERS TABLE
    // ════════════════════════════════════════════════════════════════

    /**
     * Insert a new user. Returns false if the username already exists.
     */
    public static synchronized boolean insertUser(String username, String bcryptHash) {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT OR IGNORE INTO users(username, hash) VALUES(?,?)")) {
            ps.setString(1, username);
            ps.setString(2, bcryptHash);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            System.err.println("[DB] insertUser error: " + e.getMessage());
            return false;
        }
    }

    /**
     * Fetch the stored bcrypt hash for a username, or null if not found.
     */
    public static synchronized String getUserHash(String username) {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT hash FROM users WHERE username = ?")) {
            ps.setString(1, username);
            ResultSet rs = ps.executeQuery();
            return rs.next() ? rs.getString("hash") : null;
        } catch (SQLException e) {
            System.err.println("[DB] getUserHash error: " + e.getMessage());
            return null;
        }
    }

    /** True if the username exists in the database. */
    public static synchronized boolean userExists(String username) {
        return getUserHash(username) != null;
    }

    /** Returns all registered usernames (for admin / debugging). */
    public static synchronized List<String> getAllUsernames() {
        List<String> list = new ArrayList<>();
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT username FROM users ORDER BY username")) {
            while (rs.next()) list.add(rs.getString("username"));
        } catch (SQLException e) {
            System.err.println("[DB] getAllUsernames error: " + e.getMessage());
        }
        return list;
    }

    // ════════════════════════════════════════════════════════════════
    // GROUPS TABLE
    // ════════════════════════════════════════════════════════════════

    /**
     * Create a group. Returns false if the group already exists.
     */
    public static synchronized boolean insertGroup(String groupName, String createdBy) {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT OR IGNORE INTO groups(group_name, created_by) VALUES(?,?)")) {
            ps.setString(1, groupName);
            ps.setString(2, createdBy);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            System.err.println("[DB] insertGroup error: " + e.getMessage());
            return false;
        }
    }

    /** Returns true if the group name already exists. */
    public static synchronized boolean groupExists(String groupName) {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT 1 FROM groups WHERE group_name = ?")) {
            ps.setString(1, groupName);
            return ps.executeQuery().next();
        } catch (SQLException e) {
            System.err.println("[DB] groupExists error: " + e.getMessage());
            return false;
        }
    }

    /**
     * Returns all groups as a map: groupName → createdBy.
     * Used at server startup to reload groups into GroupService's in-memory map.
     */
    public static synchronized Map<String, String> getAllGroups() {
        Map<String, String> map = new LinkedHashMap<>();
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT group_name, created_by FROM groups ORDER BY created_at")) {
            while (rs.next()) {
                map.put(rs.getString("group_name"), rs.getString("created_by"));
            }
        } catch (SQLException e) {
            System.err.println("[DB] getAllGroups error: " + e.getMessage());
        }
        return map;
    }

    // ════════════════════════════════════════════════════════════════
    // GROUP_MEMBERS TABLE
    // ════════════════════════════════════════════════════════════════

    /**
     * Add a user to a group. Silently ignored if already a member.
     */
    public static synchronized void addGroupMember(String groupName, String username) {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT OR IGNORE INTO group_members(group_name, username) VALUES(?,?)")) {
            ps.setString(1, groupName);
            ps.setString(2, username);
            ps.executeUpdate();
        } catch (SQLException e) {
            System.err.println("[DB] addGroupMember error: " + e.getMessage());
        }
    }

    /** Remove a user from a group. */
    public static synchronized void removeGroupMember(String groupName, String username) {
        try (PreparedStatement ps = conn.prepareStatement(
                "DELETE FROM group_members WHERE group_name=? AND username=?")) {
            ps.setString(1, groupName);
            ps.setString(2, username);
            ps.executeUpdate();
        } catch (SQLException e) {
            System.err.println("[DB] removeGroupMember error: " + e.getMessage());
        }
    }

    /**
     * Returns all members of a group.
     * Used at startup to reload group membership into in-memory Group objects.
     */
    public static synchronized List<String> getGroupMembers(String groupName) {
        List<String> members = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT username FROM group_members WHERE group_name=? ORDER BY joined_at")) {
            ps.setString(1, groupName);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) members.add(rs.getString("username"));
        } catch (SQLException e) {
            System.err.println("[DB] getGroupMembers error: " + e.getMessage());
        }
        return members;
    }

    /** Returns all groups a user belongs to. */
    public static synchronized List<String> getUserGroups(String username) {
        List<String> groups = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT group_name FROM group_members WHERE username=?")) {
            ps.setString(1, username);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) groups.add(rs.getString("group_name"));
        } catch (SQLException e) {
            System.err.println("[DB] getUserGroups error: " + e.getMessage());
        }
        return groups;
    }

    // ════════════════════════════════════════════════════════════════
    // MESSAGES TABLE
    // ════════════════════════════════════════════════════════════════

    /**
     * Persist a message and enforce the MAX_MESSAGES cap per channel.
     *
     * @param channel   "broadcast", "dm:alice:bob" (alphabetically sorted), "group:team-a"
     * @param sender    username of the sender
     * @param content   AES-encrypted message text (stored encrypted — server can't read it)
     * @param timestamp epoch milliseconds
     */
    public static synchronized void insertMessage(
            String channel, String sender, String content, long timestamp) {
        try {
            // Insert the new message
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO messages(channel, sender, content, timestamp) VALUES(?,?,?,?)")) {
                ps.setString(1, channel);
                ps.setString(2, sender);
                ps.setString(3, content);
                ps.setLong(4, timestamp);
                ps.executeUpdate();
            }

            // Enforce MAX_MESSAGES cap: delete oldest rows beyond the limit
            try (PreparedStatement ps = conn.prepareStatement("""
                    DELETE FROM messages
                    WHERE channel = ?
                      AND id NOT IN (
                          SELECT id FROM messages
                          WHERE channel = ?
                          ORDER BY id DESC
                          LIMIT ?
                      )
                    """)) {
                ps.setString(1, channel);
                ps.setString(2, channel);
                ps.setInt(3, MAX_MESSAGES);
                ps.executeUpdate();
            }

        } catch (SQLException e) {
            System.err.println("[DB] insertMessage error: " + e.getMessage());
        }
    }

    /**
     * Retrieve the last N messages for a channel, oldest-first.
     * Returns a list of rows: each row is {id, sender, content, timestamp}.
     */
    public static synchronized List<MessageRow> getHistory(String channel, int limit) {
        List<MessageRow> rows = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement("""
                SELECT id, sender, content, timestamp
                FROM messages
                WHERE channel = ?
                ORDER BY id DESC
                LIMIT ?
                """)) {
            ps.setString(1, channel);
            ps.setInt(2, Math.min(limit, MAX_MESSAGES));
            ResultSet rs = ps.executeQuery();

            // Collect in reverse (DESC query → reverse for oldest-first)
            List<MessageRow> tmp = new ArrayList<>();
            while (rs.next()) {
                tmp.add(new MessageRow(
                        rs.getLong("id"),
                        rs.getString("sender"),
                        rs.getString("content"),
                        rs.getLong("timestamp")
                ));
            }
            // Reverse so oldest message is first
            for (int i = tmp.size() - 1; i >= 0; i--) rows.add(tmp.get(i));

        } catch (SQLException e) {
            System.err.println("[DB] getHistory error: " + e.getMessage());
        }
        return rows;
    }

    /**
     * Normalise a DM channel key so alice↔bob and bob↔alice produce the same key.
     * Always "dm:<lower>:<higher>" alphabetically.
     */
    public static String dmChannel(String user1, String user2) {
        return "dm:" + (user1.compareTo(user2) <= 0
                ? user1 + ":" + user2
                : user2 + ":" + user1);
    }

    /** Channel key for a group. */
    public static String groupChannel(String groupName) {
        return "group:" + groupName;
    }

    // ════════════════════════════════════════════════════════════════
    // UTILITY
    // ════════════════════════════════════════════════════════════════

    /** Immutable row returned from getHistory(). */
    public record MessageRow(long id, String sender, String content, long timestamp) {}

    /** Execute a DDL or pragma statement (no result set needed). */
    private static void exec(String sql) throws SQLException {
        try (Statement st = conn.createStatement()) {
            st.execute(sql);
        }
    }

    /** Print a quick summary of every table's row count — handy for debugging. */
    public static synchronized void printStats() {
        String[] tables = {"users", "groups", "group_members", "messages"};
        for (String t : tables) {
            try (Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM " + t)) {
                System.out.printf("[DB] %-16s %d rows%n", t, rs.getInt(1));
            } catch (SQLException e) {
                System.err.println("[DB] stats error for " + t + ": " + e.getMessage());
            }
        }
    }
}
