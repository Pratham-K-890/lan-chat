package com.lanchat.common.model;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Represents a chat group.
 * The Set<String> is backed by ConcurrentHashMap.newKeySet() so
 * addMember/removeMember/isMember are all thread-safe without external locking.
 */
public class Group {

    private final String      name;
    private final String      createdBy;
    private final Set<String> members = ConcurrentHashMap.newKeySet();

    public Group(String name, String createdBy) {
        this.name      = name;
        this.createdBy = createdBy;
    }

    public void    addMember(String username)    { members.add(username); }
    public void    removeMember(String username) { members.remove(username); }
    public boolean isMember(String username)     { return members.contains(username); }

    public Set<String> getMembers()  { return members; }
    public String      getName()     { return name; }
    public String      getCreatedBy(){ return createdBy; }

    @Override
    public String toString() {
        return "Group{" + name + ", members=" + members + "}";
    }
}
