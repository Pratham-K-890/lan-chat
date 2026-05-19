package com.lanchat.client;

import com.lanchat.common.crypto.AesUtil;
import com.lanchat.common.protocol.Envelope;
import com.lanchat.common.protocol.MessageType;
import com.lanchat.common.util.JsonUtil;

/**
 * Runs on a background thread. Reads newline-delimited JSON from the server,
 * decrypts text fields, and dispatches to a {@link MessageCallback}.
 *
 * Design: callback interface keeps this class decoupled from any UI layer.
 *   - Desktop: ConsoleUI implements MessageCallback and prints to stdout
 *   - Android: the Activity / ViewModel implements MessageCallback and posts
 *              to LiveData / StateFlow so the UI thread sees updates safely
 *
 * Reconnect: if readLine() returns null (server closed), the listener calls
 * connection.reconnect() then loops back. After reconnect the caller must
 * re-authenticate — the server drops all session state on a new connection.
 */
public class MessageListener implements Runnable {

    /** All server-to-client events funnel through this interface. */
    public interface MessageCallback {
        void onAuthSuccess(String username, String token);
        void onAuthFailure(String reason);
        void onBroadcast(String from, String text, long timestamp);
        void onPrivateMessage(String from, String text);
        void onGroupMessage(String from, String groupName, String text);
        void onUserJoined(String username);
        void onUserLeft(String username);
        void onUserList(com.fasterxml.jackson.databind.JsonNode usersArray);
        void onGroupEvent(String action, String groupName);  // CREATED / JOINED / LEFT
        void onFileOffer(String from, String fileName, long fileSize, String transferId);
        void onFileAccept(String transferId);
        void onFileChunk(String transferId, int chunkIndex, String base64Data);
        void onFileDone(String transferId);
        void onVoiceCallOffer(String from, String callId, String callerIp);
        void onVoiceCallAccept(String from, String callId, int udpPort, String calleeIp);
        void onVoiceCallReject(String from, String callId);
        void onVoiceCallHangup(String callId);
        void onVoiceCallBusy(String callId, String reason);
        void onError(String code, String message);
        /**
         * Called when the server delivers persisted message history.
         * @param channel   "broadcast", "dm:user1:user2", or "group:groupName"
         * @param messages  ordered list of historical messages (oldest first)
         */
        void onHistoryReceived(String channel, java.util.List<HistoryMessage> messages);
        void onDisconnected();    // called when connection drops
        void onReconnected();     // called after successful reconnect
    }

    private final ConnectionManager connection;
    private final MessageCallback   callback;
    private volatile boolean        running = true;

    public MessageListener(ConnectionManager connection, MessageCallback callback) {
        this.connection = connection;
        this.callback   = callback;
    }

    // ── Main loop ────────────────────────────────────────────────────

    @Override
    public void run() {
        while (running) {
            try {
                var reader = connection.getReader();
                if (reader == null) {
                    Thread.sleep(500);
                    continue;
                }

                String line;
                while ((line = reader.readLine()) != null) {
                    if (!running) break;
                    if (line.isBlank()) continue;

                    Envelope env;
                    try {
                        env = JsonUtil.fromJson(line);
                    } catch (RuntimeException e) {
                        System.err.println("[LISTENER] Bad JSON: " + e.getMessage());
                        continue;
                    }
                    dispatch(env);
                }

                // readLine() returned null → server closed connection
                if (running) {
                    callback.onDisconnected();
                    connection.reconnect();
                    callback.onReconnected();
                }

            } catch (Exception e) {
                if (running) {
                    System.err.println("[LISTENER] Error: " + e.getMessage());
                    callback.onDisconnected();
                    connection.reconnect();
                    callback.onReconnected();
                }
            }
        }
    }

    public void stop() { running = false; }

    // ── Dispatcher ───────────────────────────────────────────────────

    private void dispatch(Envelope env) {
        switch (env.type) {

            case AUTH_SUCCESS -> callback.onAuthSuccess(
                    env.payload.get("username").asText(),
                    env.payload.get("token").asText());

            case AUTH_FAILURE -> callback.onAuthFailure(
                    env.payload.get("reason").asText());

            case BROADCAST -> {
                String from      = env.payload.get("from").asText();
                String raw       = env.payload.get("text").asText();
                long   timestamp = env.payload.has("timestamp")
                        ? env.payload.get("timestamp").asLong() : 0L;
                callback.onBroadcast(from, tryDecrypt(raw), timestamp);
            }

            case PRIVATE_MSG -> {
                String from = env.payload.get("from").asText();
                String raw  = env.payload.get("text").asText();
                callback.onPrivateMessage(from, tryDecrypt(raw));
            }

            case GROUP_MSG -> {
                String from      = env.payload.get("from").asText();
                String groupName = env.payload.get("groupName").asText();
                String raw       = env.payload.get("text").asText();
                callback.onGroupMessage(from, groupName, tryDecrypt(raw));
            }

            case USER_JOINED -> callback.onUserJoined(
                    env.payload.get("username").asText());

            case USER_LEFT -> callback.onUserLeft(
                    env.payload.get("username").asText());

            case USER_LIST -> callback.onUserList(env.payload.get("users"));

            case GROUP_LIST -> callback.onGroupEvent(
                    env.payload.get("action").asText(),
                    env.payload.get("groupName").asText());

            case FILE_OFFER -> callback.onFileOffer(
                    env.payload.get("from").asText(),
                    env.payload.get("fileName").asText(),
                    env.payload.get("fileSize").asLong(),
                    env.payload.get("transferId").asText());

            case FILE_ACCEPT -> callback.onFileAccept(
                    env.payload.get("transferId").asText());

            case FILE_CHUNK -> callback.onFileChunk(
                    env.payload.get("transferId").asText(),
                    env.payload.get("chunkIndex").asInt(),
                    env.payload.get("data").asText());

            case FILE_DONE -> callback.onFileDone(
                    env.payload.get("transferId").asText());

            case VOICE_CALL_OFFER -> callback.onVoiceCallOffer(
                    env.payload.get("from").asText(),
                    env.payload.get("callId").asText(),
                    env.payload.has("callerIp")
                            ? env.payload.get("callerIp").asText() : "");

            case VOICE_CALL_ACCEPT -> callback.onVoiceCallAccept(
                    env.payload.get("from").asText(),
                    env.payload.get("callId").asText(),
                    env.payload.get("udpPort").asInt(),
                    env.payload.has("calleeIp")
                            ? env.payload.get("calleeIp").asText() : "");

            case VOICE_CALL_REJECT -> callback.onVoiceCallReject(
                    env.payload.get("from").asText(),
                    env.payload.get("callId").asText());

            case VOICE_CALL_HANGUP -> callback.onVoiceCallHangup(
                    env.payload.get("callId").asText());

            case VOICE_CALL_BUSY -> callback.onVoiceCallBusy(
                    env.payload.get("callId").asText(),
                    env.payload.get("reason").asText());

            case ERROR -> callback.onError(
                    env.payload.get("code").asText(),
                    env.payload.get("message").asText());

            case PONG -> {} // keep-alive reply — no action needed

            case HISTORY_RESPONSE -> {
                String channel = env.payload.get("channel").asText();
                var messagesNode = env.payload.get("messages");
                var histList = new java.util.ArrayList<HistoryMessage>();
                if (messagesNode != null && messagesNode.isArray()) {
                    for (var node : messagesNode) {
                        histList.add(new HistoryMessage(
                                node.get("sender").asText(),
                                tryDecrypt(node.get("content").asText()),
                                node.get("timestamp").asLong()
                        ));
                    }
                }
                callback.onHistoryReceived(channel, histList);
            }

            default -> System.out.println("[LISTENER] Unknown type: " + env.type);
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────

    /**
     * Try AES decrypt; fall back to raw text for unencrypted system messages
     * (e.g. "alice joined the chat" sent by MessageRouter.broadcastSystemMessage).
     */
    private static String tryDecrypt(String raw) {
        try {
            return AesUtil.decrypt(raw);
        } catch (Exception e) {
            return raw;
        }
    }

    /**
     * A single message from persisted history.
     * Already decrypted by tryDecrypt() before being passed to onHistoryReceived().
     */
    public record HistoryMessage(String sender, String text, long timestamp) {}
}
