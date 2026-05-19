package com.lanchat.server.handler;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.lanchat.common.protocol.Envelope;
import com.lanchat.common.protocol.MessageType;
import com.lanchat.common.util.JsonUtil;
import com.lanchat.server.ClientHandler;
import com.lanchat.server.service.PresenceTracker;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Handles voice call signaling over the existing TCP connection.
 *
 * The actual audio travels peer-to-peer over UDP (port 9091) — this server
 * is NOT in the audio path. It only routes the 4 control messages:
 *
 *   VOICE_CALL_OFFER   caller  → server → callee
 *   VOICE_CALL_ACCEPT  callee  → server → caller  (includes callee's UDP port)
 *   VOICE_CALL_REJECT  callee  → server → caller
 *   VOICE_CALL_HANGUP  either  → server → other party
 *
 * Android note: On Android, audio capture/playback uses AudioRecord/AudioTrack
 * (not javax.sound). The signaling messages are identical — only the audio
 * implementation differs between desktop and Android.
 */
public final class VoiceCallSignalingHandler {

    // callId → [callerUsername, calleeUsername]
    private static final ConcurrentHashMap<String, String[]> activeCalls
            = new ConcurrentHashMap<>();

    // username → callId  (to detect busy state)
    private static final ConcurrentHashMap<String, String> userInCall
            = new ConcurrentHashMap<>();

    private VoiceCallSignalingHandler() {}

    // ── Offer ────────────────────────────────────────────────────────

    public static void handleOffer(ClientHandler caller, Envelope env) {
        String to     = env.payload.get("to").asText();
        String callId = env.payload.get("callId").asText();

        ClientHandler callee = PresenceTracker.get(to);
        if (callee == null) {
            caller.sendError("USER_NOT_FOUND", to + " is not online");
            return;
        }

        // Callee busy?
        if (userInCall.containsKey(to)) {
            caller.send(JsonUtil.envelope(MessageType.VOICE_CALL_BUSY,
                    "callId", callId,
                    "reason", to + " is already in a call"));
            return;
        }

        // Forward offer to callee with caller's username and IP
        ((ObjectNode) env.payload)
                .put("from",      caller.getUsername())
                .put("callerIp",  resolveIp(caller, env.payload));
        callee.send(new Envelope(MessageType.VOICE_CALL_OFFER, env.payload));

        System.out.println("[VOICE] Offer: " + caller.getUsername() + " → " + to);
    }

    // ── Accept ───────────────────────────────────────────────────────

    public static void handleAccept(ClientHandler callee, Envelope env) {
        String callId     = env.payload.get("callId").asText();
        String callerName = env.payload.get("to").asText(); // "to" = original caller

        ClientHandler caller = PresenceTracker.get(callerName);
        if (caller == null) {
            callee.sendError("USER_NOT_FOUND", "Caller disconnected");
            return;
        }

        activeCalls.put(callId, new String[]{callerName, callee.getUsername()});
        userInCall.put(callerName,          callId);
        userInCall.put(callee.getUsername(), callId);

        // Tell caller: accepted, here's callee's IP + UDP port for audio
        ((ObjectNode) env.payload)
                .put("from",     callee.getUsername())
                .put("calleeIp", resolveIp(callee, env.payload));
        caller.send(new Envelope(MessageType.VOICE_CALL_ACCEPT, env.payload));

        System.out.println("[VOICE] Accepted: " + callId);
    }

    // ── Reject ───────────────────────────────────────────────────────

    public static void handleReject(ClientHandler callee, Envelope env) {
        String callerName = env.payload.get("to").asText();
        ClientHandler caller = PresenceTracker.get(callerName);
        if (caller != null) {
            ((ObjectNode) env.payload).put("from", callee.getUsername());
            caller.send(new Envelope(MessageType.VOICE_CALL_REJECT, env.payload));
        }
        System.out.println("[VOICE] Rejected: " + env.payload.get("callId").asText());
    }

    // ── Hangup ───────────────────────────────────────────────────────

    public static void handleHangup(ClientHandler initiator, Envelope env) {
        String callId  = env.payload.get("callId").asText();
        String[] parties = activeCalls.remove(callId);

        if (parties != null) {
            userInCall.remove(parties[0]);
            userInCall.remove(parties[1]);

            // Notify the other party
            for (String name : parties) {
                if (!name.equals(initiator.getUsername())) {
                    ClientHandler other = PresenceTracker.get(name);
                    if (other != null) {
                        other.send(new Envelope(MessageType.VOICE_CALL_HANGUP, env.payload));
                    }
                }
            }
        }
        System.out.println("[VOICE] Hangup: " + callId);
    }

    // ── Disconnect cleanup ───────────────────────────────────────────

    /** Called by ClientHandler.handleDisconnect() — end any active call cleanly. */
    public static void onUserDisconnected(String username) {
        String callId = userInCall.remove(username);
        if (callId == null) return;

        String[] parties = activeCalls.remove(callId);
        if (parties == null) return;

        // Notify the other party that the call ended
        for (String name : parties) {
            if (!name.equals(username)) {
                userInCall.remove(name);
                ClientHandler other = PresenceTracker.get(name);
                if (other != null) {
                    other.send(JsonUtil.envelope(MessageType.VOICE_CALL_HANGUP,
                            "callId", callId,
                            "reason", username + " disconnected"));
                }
            }
        }
    }

    /**
     * Returns the client's real LAN IP for UDP audio routing.
     *
     * The HOST connects to its own server via 127.0.0.1 (loopback).
     * getRemoteIp() would return "127.0.0.1" which is useless for UDP audio
     * because the callee would send audio to its own loopback.
     *
     * Fix: the client includes its real LAN IP ("myIp") in OFFER/ACCEPT packets.
     * We use that when the TCP remoteIp is a loopback address.
     */
    private static String resolveIp(com.lanchat.server.ClientHandler client,
                                    com.fasterxml.jackson.databind.JsonNode payload) {
        String remoteIp = client.getRemoteIp();
        if (remoteIp.equals("127.0.0.1") || remoteIp.equals("::1") || remoteIp.startsWith("127.")) {
            // Loopback — client is the HOST connecting to its own server
            // Use the real LAN IP the client advertised in the packet
            if (payload != null && payload.has("myIp")) {
                String myIp = payload.get("myIp").asText();
                if (!myIp.isBlank() && !myIp.equals("127.0.0.1")) {
                    return myIp;
                }
            }
        }
        return remoteIp;
    }
}