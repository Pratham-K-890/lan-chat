package com.lanchat.server;

import com.lanchat.common.protocol.Envelope;
import com.lanchat.common.protocol.MessageType;
import com.lanchat.common.util.JsonUtil;
import com.lanchat.server.handler.FileTransferHandler;
import com.lanchat.server.handler.VoiceCallSignalingHandler;
import com.lanchat.server.service.GroupService;
import com.lanchat.server.service.MessageRouter;
import com.lanchat.server.service.UserService;

import java.io.*;
import java.net.Socket;

/**
 * Runnable that lives on its own thread and owns the full lifecycle
 * of one connected client socket.
 *
 * Lifecycle:
 *   constructor → run() → read loop → handleDisconnect() → thread exits
 *
 * Thread safety:
 *   send() is synchronized on this instance's monitor so multiple threads
 *   (MessageRouter, GroupService, etc.) can all call handler.send() safely.
 */
public class ClientHandler implements Runnable {

    private final Socket socket;
    private final String remoteIp;   // stored so VoiceCallManager can resolve peer IP
    private PrintWriter  out;
    private String       username;   // null until successfully authenticated

    public ClientHandler(Socket socket, String remoteIp) {
        this.socket   = socket;
        this.remoteIp = remoteIp;
    }

    // ── Main read loop ────────────────────────────────────────────────

    @Override
    public void run() {
        try (
            BufferedReader in = new BufferedReader(
                    new InputStreamReader(socket.getInputStream(), "UTF-8"));
            PrintWriter writer = new PrintWriter(
                    new OutputStreamWriter(socket.getOutputStream(), "UTF-8"), true)
        ) {
            this.out = writer;
            String line;

            // Read newline-delimited JSON until the client disconnects (line == null = EOF)
            while ((line = in.readLine()) != null) {
                if (line.isBlank()) continue; // ignore empty keep-alive lines

                Envelope env;
                try {
                    env = JsonUtil.fromJson(line);
                } catch (RuntimeException e) {
                    sendError("PARSE_ERROR", "Malformed JSON: " + e.getMessage());
                    continue; // don't crash the handler on bad input
                }

                dispatch(env);
            }

        } catch (IOException e) {
            System.out.println("[HANDLER] IO error for "
                    + (username != null ? username : remoteIp)
                    + ": " + e.getMessage());
        } finally {
            handleDisconnect();
        }
    }

    // ── Dispatcher ───────────────────────────────────────────────────

    /**
     * Route each incoming envelope to the correct service method.
     * All phases plug in here — add new cases as phases are implemented.
     */
    private void dispatch(Envelope env) {
        switch (env.type) {
            // Phase 1–2: Auth
            case REGISTER        -> UserService.handleRegister(this, env);
            case LOGIN           -> UserService.handleLogin(this, env);

            // Phase 3–4: Messaging & Groups
            case BROADCAST       -> MessageRouter.broadcast(this, env);
            case PRIVATE_MSG     -> MessageRouter.sendPrivate(this, env);
            case GROUP_MSG       -> MessageRouter.sendToGroup(this, env);
            case CREATE_GROUP    -> GroupService.create(this, env);
            case JOIN_GROUP      -> GroupService.join(this, env);
            case LEAVE_GROUP     -> GroupService.leave(this, env);

            // Phase 7: File transfer
            case FILE_OFFER      -> FileTransferHandler.offer(this, env);
            case FILE_ACCEPT     -> FileTransferHandler.accept(this, env);
            case FILE_CHUNK      -> FileTransferHandler.chunk(this, env);
            case FILE_DONE       -> FileTransferHandler.done(this, env);

            // Phase 8: Voice signaling
            case VOICE_CALL_OFFER  -> VoiceCallSignalingHandler.handleOffer(this, env);
            case VOICE_CALL_ACCEPT -> VoiceCallSignalingHandler.handleAccept(this, env);
            case VOICE_CALL_REJECT -> VoiceCallSignalingHandler.handleReject(this, env);
            case VOICE_CALL_HANGUP -> VoiceCallSignalingHandler.handleHangup(this, env);

            // System
            case HISTORY_REQUEST -> UserService.handleHistoryRequest(this, env);

            // Keep-alive ──────────────────────────────────────────
            case PING            -> send(JsonUtil.envelope(MessageType.PONG));

            default -> sendError("UNKNOWN_TYPE",
                    "Unrecognized message type: " + env.type);
        }
    }

    // ── Send helpers ─────────────────────────────────────────────────

    /**
     * Thread-safe send.
     * synchronized prevents two threads from interleaving bytes on the same PrintWriter.
     */
    public synchronized void send(Envelope env) {
        if (out != null) {
            out.println(JsonUtil.toJson(env));
        }
    }

    /** Convenience: build and send an ERROR envelope in one call. */
    public void sendError(String code, String message) {
        send(JsonUtil.envelope(MessageType.ERROR, "code", code, "message", message));
    }

    // ── Disconnect cleanup ───────────────────────────────────────────

    private void handleDisconnect() {
        if (username != null) {
            ChatServer.onlineUsers.remove(username);
            // Notify remaining users: system feed message + USER_LEFT so online panels update
            MessageRouter.broadcastSystemMessage(username + " left the chat", this);
            Envelope leaveEnv = new Envelope(MessageType.USER_LEFT,
                    JsonUtil.buildPayload("username", username));
            ChatServer.onlineUsers.values().forEach(h -> h.send(leaveEnv));
            // Clean up any active voice call
            VoiceCallSignalingHandler.onUserDisconnected(username);
            System.out.println("[SERVER] " + username + " disconnected.");
        } else {
            System.out.println("[SERVER] Unauthenticated client disconnected: " + remoteIp);
        }

        try { socket.close(); } catch (IOException ignored) {}
    }

    // ── Accessors ────────────────────────────────────────────────────

    public String getUsername()        { return username; }
    public void   setUsername(String u){ this.username = u; }
    public String getRemoteIp()        { return remoteIp; }
}
