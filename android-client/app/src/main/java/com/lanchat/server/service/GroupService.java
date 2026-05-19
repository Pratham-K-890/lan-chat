package com.lanchat.server.service;

import com.lanchat.common.model.Group;
import com.lanchat.common.protocol.Envelope;
import com.lanchat.common.protocol.MessageType;
import com.lanchat.common.util.JsonUtil;
import com.lanchat.server.ClientHandler;
import com.lanchat.server.db.Database;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Group CRUD — backed by SQLite `groups` and `group_members` tables.
 *
 * In-memory map mirrors the DB for fast routing. On server start, loadFromDb()
 * is called to rebuild the in-memory state from persisted data, so all groups
 * and memberships survive a server restart.
 *
 * Thread safety: ConcurrentHashMap for the groups map; DB writes are synchronized
 * inside Database.java; Group.members is a ConcurrentHashMap.newKeySet().
 */
public final class GroupService {

    // in-memory mirror: groupName → Group object
    private static final ConcurrentHashMap<String, Group> groups
            = new ConcurrentHashMap<>();

    private GroupService() {}

    // ── Startup: reload from SQLite ───────────────────────────────────

    /**
     * Called once by ChatServer.main() / EmbeddedServer.startEmbedded() after
     * Database.init(). Rebuilds all Group objects from persisted data.
     */
    public static void loadFromDb() {
        groups.clear();
        var allGroups = Database.getAllGroups(); // groupName → createdBy

        for (var entry : allGroups.entrySet()) {
            String groupName = entry.getKey();
            String createdBy = entry.getValue();

            Group group = new Group(groupName, createdBy);
            // Reload members
            Database.getGroupMembers(groupName).forEach(group::addMember);
            groups.put(groupName, group);
        }

        System.out.println("[GROUP] Loaded " + groups.size()
                + " group(s) from database.");
    }

    // ── Create ────────────────────────────────────────────────────────

    public static void create(ClientHandler creator, Envelope env) {
        String groupName = env.payload.get("groupName").asText().strip();

        if (groupName.isBlank()) {
            creator.sendError("INVALID_NAME", "Group name cannot be empty");
            return;
        }
        if (groups.containsKey(groupName)) {
            creator.sendError("GROUP_EXISTS", "Group '" + groupName + "' already exists");
            return;
        }

        // Persist to DB first
        boolean created = Database.insertGroup(groupName, creator.getUsername());
        if (!created) {
            creator.sendError("GROUP_EXISTS", "Group '" + groupName + "' already exists");
            return;
        }

        // Add creator as first member (DB + in-memory)
        Database.addGroupMember(groupName, creator.getUsername());

        Group group = new Group(groupName, creator.getUsername());
        group.addMember(creator.getUsername());
        groups.put(groupName, group);

        creator.send(JsonUtil.envelope(MessageType.GROUP_LIST,
                "action", "CREATED", "groupName", groupName));

        System.out.println("[GROUP] Created: " + groupName + " by " + creator.getUsername());
    }

    // ── Join ──────────────────────────────────────────────────────────

    public static void join(ClientHandler client, Envelope env) {
        String groupName = env.payload.get("groupName").asText();
        Group  group     = groups.get(groupName);

        if (group == null) {
            client.sendError("GROUP_NOT_FOUND", "No group named '" + groupName + "'");
            return;
        }
        if (group.isMember(client.getUsername())) {
            client.sendError("ALREADY_MEMBER", "Already in group '" + groupName + "'");
            return;
        }

        // Persist then update in-memory
        Database.addGroupMember(groupName, client.getUsername());
        group.addMember(client.getUsername());

        client.send(JsonUtil.envelope(MessageType.GROUP_LIST,
                "action", "JOINED", "groupName", groupName));

        MessageRouter.broadcastToGroup(group,
                client.getUsername() + " joined group " + groupName, client);

        System.out.println("[GROUP] " + client.getUsername() + " joined: " + groupName);
    }

    // ── Leave ─────────────────────────────────────────────────────────

    public static void leave(ClientHandler client, Envelope env) {
        String groupName = env.payload.get("groupName").asText();
        Group  group     = groups.get(groupName);

        if (group == null) return;

        // Persist then update in-memory
        Database.removeGroupMember(groupName, client.getUsername());
        group.removeMember(client.getUsername());

        client.send(JsonUtil.envelope(MessageType.GROUP_LIST,
                "action", "LEFT", "groupName", groupName));

        MessageRouter.broadcastToGroup(group,
                client.getUsername() + " left group " + groupName, client);

        System.out.println("[GROUP] " + client.getUsername() + " left: " + groupName);
    }

    // ── Queries ───────────────────────────────────────────────────────

    public static Group getGroup(String name) { return groups.get(name); }

    /** Remove a disconnected user from all groups (in-memory only — membership persists in DB). */
    public static void removeFromAllGroups(String username) {
        groups.values().forEach(g -> g.removeMember(username));
    }
}
