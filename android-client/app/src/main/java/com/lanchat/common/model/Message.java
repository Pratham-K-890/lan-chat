package com.lanchat.common.model;

/**
 * Represents a chat message.
 * Used as a data carrier — not sent directly over the wire
 * (the Envelope/payload pattern is used for wire format).
 * Useful for storing message history on the client side.
 */
public class Message {

    private String from;
    private String to;          // null for broadcast messages
    private String groupName;   // null for non-group messages
    private String text;
    private long   timestamp;
    private Type   messageType;

    public enum Type { BROADCAST, PRIVATE, GROUP }

    public Message() {}

    public Message(String from, String text, long timestamp, Type messageType) {
        this.from        = from;
        this.text        = text;
        this.timestamp   = timestamp;
        this.messageType = messageType;
    }

    // ── Getters & setters ─────────────────────────────────────────────

    public String getFrom()           { return from; }
    public void   setFrom(String f)   { this.from = f; }

    public String getTo()             { return to; }
    public void   setTo(String t)     { this.to = t; }

    public String getGroupName()               { return groupName; }
    public void   setGroupName(String g)       { this.groupName = g; }

    public String getText()           { return text; }
    public void   setText(String t)   { this.text = t; }

    public long   getTimestamp()      { return timestamp; }
    public void   setTimestamp(long ts) { this.timestamp = ts; }

    public Type   getMessageType()            { return messageType; }
    public void   setMessageType(Type mt)     { this.messageType = mt; }

    @Override
    public String toString() {
        return "[" + messageType + "] " + from + ": " + text;
    }
}
