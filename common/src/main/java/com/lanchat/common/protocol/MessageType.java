package com.lanchat.common.protocol;

/**
 * Every packet type that travels over the wire.
 * Both server and Android client import this from common.jar.
 */
public enum MessageType {

    // ── Auth ──────────────────────────────────────────────
    REGISTER,           // client → server
    LOGIN,              // client → server
    AUTH_SUCCESS,       // server → client: ok + token
    AUTH_FAILURE,       // server → client: bad credentials

    // ── Presence ──────────────────────────────────────────
    USER_JOINED,        // server → all
    USER_LEFT,          // server → all
    USER_LIST,          // server → client: snapshot of online users

    // ── Messaging ─────────────────────────────────────────
    BROADCAST,          // client → server → all
    PRIVATE_MSG,        // client → server → one
    GROUP_MSG,          // client → server → group members

    // ── Groups ────────────────────────────────────────────
    CREATE_GROUP,
    JOIN_GROUP,
    LEAVE_GROUP,
    GROUP_LIST,         // server → client: action + groupName

    // ── History (new) ─────────────────────────────────────
    // Client requests chat history on login; server replies with stored messages.
    HISTORY_REQUEST,    // client → server: {channel, limit}
    HISTORY_RESPONSE,   // server → client: {channel, messages:[{sender,content,timestamp}]}

    // ── File Transfer ─────────────────────────────────────
    FILE_OFFER,
    FILE_ACCEPT,
    FILE_CHUNK,
    FILE_DONE,

    // ── Voice Signaling (TCP) ─────────────────────────────
    VOICE_CALL_OFFER,
    VOICE_CALL_ACCEPT,
    VOICE_CALL_REJECT,
    VOICE_CALL_HANGUP,
    VOICE_CALL_BUSY,

    // ── System ────────────────────────────────────────────
    ERROR,
    PING,
    PONG
}
