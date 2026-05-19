package com.lanchat.client;

import com.lanchat.common.crypto.AesUtil;
import com.lanchat.common.protocol.Envelope;
import com.lanchat.common.protocol.MessageType;
import com.lanchat.common.util.JsonUtil;

/**
 * Encrypts and sends messages to the server over the TCP connection.
 *
 * All public methods are safe to call from any thread — they delegate to
 * send() which is synchronized on the ConnectionManager's PrintWriter.
 *
 * Encryption rule:
 *   - Message TEXT fields (broadcast, PM, group) → AES-256 encrypted
 *   - Metadata fields (to, groupName, from, timestamps) → plain (server needs them to route)
 *   - Auth payloads (username, password) → plain (TLS would protect these in production)
 *
 * Android usage:
 *   Inject a MessageSender into your ViewModel. Call its methods from
 *   a background coroutine / thread — never block the UI thread.
 */
public class MessageSender {

    private final ConnectionManager connection;

    /**
     * The caller's real LAN IP — set by ServerService/ChatService before any voice calls.
     * Used in OFFER/ACCEPT packets so the remote peer knows where to send UDP audio.
     * Without this, HOST (127.0.0.1 loopback) can't receive audio from callee.
     */
    public static volatile String MY_LAN_IP = "";

    public MessageSender(ConnectionManager connection) {
        this.connection = connection;
    }

    // ── Auth ─────────────────────────────────────────────────────────

    public void sendRegister(String username, String password) {
        send(JsonUtil.envelope(MessageType.REGISTER,
                "username", username, "password", password));
    }

    public void sendLogin(String username, String password) {
        send(JsonUtil.envelope(MessageType.LOGIN,
                "username", username, "password", password));
    }

    // ── Messaging ────────────────────────────────────────────────────

    /** Broadcast an AES-encrypted message to all users. */
    public void sendBroadcast(String text) {
        send(JsonUtil.envelope(MessageType.BROADCAST,
                "text", AesUtil.encrypt(text)));
    }

    /** Send an AES-encrypted private message to one user. */
    public void sendPrivateMessage(String toUsername, String text) {
        send(JsonUtil.envelope(MessageType.PRIVATE_MSG,
                "to", toUsername,
                "text", AesUtil.encrypt(text)));
    }

    /** Send an AES-encrypted message to a group. */
    public void sendGroupMessage(String groupName, String text) {
        send(JsonUtil.envelope(MessageType.GROUP_MSG,
                "groupName", groupName,
                "text", AesUtil.encrypt(text)));
    }

    // ── Groups ────────────────────────────────────────────────────────

    public void sendCreateGroup(String groupName) {
        send(JsonUtil.envelope(MessageType.CREATE_GROUP, "groupName", groupName));
    }

    public void sendJoinGroup(String groupName) {
        send(JsonUtil.envelope(MessageType.JOIN_GROUP, "groupName", groupName));
    }

    public void sendLeaveGroup(String groupName) {
        send(JsonUtil.envelope(MessageType.LEAVE_GROUP, "groupName", groupName));
    }

    // ── File Transfer ─────────────────────────────────────────────────

    public void sendFileOffer(String toUsername, String fileName,
                               long fileSize, String transferId) {
        send(JsonUtil.envelope(MessageType.FILE_OFFER,
                "to",         toUsername,
                "fileName",   fileName,
                "fileSize",   fileSize,
                "transferId", transferId));
    }

    public void sendFileAccept(String transferId) {
        send(JsonUtil.envelope(MessageType.FILE_ACCEPT, "transferId", transferId));
    }

    public void sendFileChunk(String transferId, int chunkIndex, String base64Data) {
        send(JsonUtil.envelope(MessageType.FILE_CHUNK,
                "transferId", transferId,
                "chunkIndex", chunkIndex,
                "data",       base64Data));
    }

    public void sendFileDone(String transferId) {
        send(JsonUtil.envelope(MessageType.FILE_DONE, "transferId", transferId));
    }

    // ── Voice Signaling ───────────────────────────────────────────────

    public void sendVoiceCallOffer(String toUsername, String callId) {
        send(JsonUtil.envelope(MessageType.VOICE_CALL_OFFER,
                "to", toUsername, "callId", callId, "myIp", MY_LAN_IP));
    }

    public void sendVoiceCallAccept(String callerUsername, String callId, int udpPort) {
        send(JsonUtil.envelope(MessageType.VOICE_CALL_ACCEPT,
                "to",      callerUsername,
                "callId",  callId,
                "udpPort", udpPort,
                "myIp",    MY_LAN_IP));
    }

    public void sendVoiceCallReject(String callerUsername, String callId) {
        send(JsonUtil.envelope(MessageType.VOICE_CALL_REJECT,
                "to", callerUsername, "callId", callId));
    }

    public void sendVoiceCallHangup(String callId) {
        send(JsonUtil.envelope(MessageType.VOICE_CALL_HANGUP, "callId", callId));
    }

    // ── Keep-alive ────────────────────────────────────────────────────

    public void sendPing() {
        send(new Envelope(MessageType.PING, JsonUtil.emptyPayload()));
    }

    // ── History ───────────────────────────────────────────────────────

    /**
     * Request chat history for a channel.
     * @param channel  "broadcast", "dm:user1:user2" (sorted), or "group:groupName"
     * @param limit    number of messages to retrieve (max 200)
     */
    public void requestHistory(String channel, int limit) {
        send(JsonUtil.envelope(MessageType.HISTORY_REQUEST,
                "channel", channel,
                "limit",   limit));
    }

    /** Convenience: request the last 50 broadcast messages. */
    public void requestBroadcastHistory() {
        requestHistory("broadcast", 50);
    }

    /** Convenience: request DM history between two users. */
    public void requestDmHistory(String myUsername, String otherUsername, int limit) {
        // Channel must match server's dmChannel() — alphabetically sorted
        String channel = myUsername.compareTo(otherUsername) <= 0
                ? "dm:" + myUsername + ":" + otherUsername
                : "dm:" + otherUsername + ":" + myUsername;
        requestHistory(channel, limit);
    }

    /** Convenience: request the last 50 messages of a group. */
    public void requestGroupHistory(String groupName, int limit) {
        requestHistory("group:" + groupName, limit);
    }

    // ── Core send ────────────────────────────────────────────────────

    /**
     * Serialize and write one envelope to the socket.
     * The PrintWriter auto-flushes on println (set in ConnectionManager).
     * synchronized so multiple threads can safely call send() concurrently.
     */
    public synchronized void send(Envelope env) {
        var writer = connection.getWriter();
        if (writer != null) {
            writer.println(JsonUtil.toJson(env));
        } else {
            System.err.println("[SENDER] Cannot send — not connected.");
        }
    }
}
