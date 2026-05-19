package com.lanchat.common.model;

/**
 * Represents an authenticated user.
 * On the server side: stored in UserService (username → bcrypt hash).
 * On the client side: holds the local session (username + token).
 */
public class User {

    private String username;
    private String sessionToken;  // UUID issued on successful login (client only)
    private boolean online;

    public User() {}

    public User(String username) {
        this.username = username;
    }

    // ── Getters & setters ─────────────────────────────────────────────

    public String  getUsername()                   { return username; }
    public void    setUsername(String u)           { this.username = u; }

    public String  getSessionToken()               { return sessionToken; }
    public void    setSessionToken(String t)       { this.sessionToken = t; }

    public boolean isOnline()                      { return online; }
    public void    setOnline(boolean o)            { this.online = o; }

    @Override
    public String toString() { return username + (online ? " [online]" : " [offline]"); }
}
