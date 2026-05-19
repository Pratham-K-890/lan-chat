package com.lanchat.client;

import com.lanchat.common.crypto.AesUtil;
import com.lanchat.common.protocol.Envelope;
import com.lanchat.common.protocol.MessageType;
import com.lanchat.common.util.JsonUtil;

import java.io.*;
import java.net.Socket;
import java.util.Scanner;

/**
 * CONSOLE PLACEHOLDER CLIENT — for testing the server backend only.
 *
 * This is NOT the Android client. The real mobile client lives in:
 *   android-client/   ← Android Studio Kotlin project
 *
 * Usage:
 *   java -jar lanchat-client.jar <server-ip>
 *
 * Commands in the chat loop:
 *   /register <user> <pass>   — register a new account
 *   /login    <user> <pass>   — log in
 *   /pm       <user> <msg>    — private message
 *   /group    <grp>  <msg>    — group message
 *   /join     <grp>           — join a group
 *   /create   <grp>           — create a group
 *   /quit                     — disconnect
 *   <any text>                — broadcast message
 */
public class ChatClient {

    private static final int    PORT       = 9090;
    private static final String DEFAULT_IP = "127.0.0.1";

    public static void main(String[] args) throws IOException {
        String serverIp = (args.length > 0) ? args[0] : DEFAULT_IP;

        System.out.println("╔══════════════════════════════════════╗");
        System.out.println("║   LAN Chat — Console Test Client     ║");
        System.out.println("╚══════════════════════════════════════╝");
        System.out.println("Connecting to " + serverIp + ":" + PORT + " ...");

        Socket socket = new Socket(serverIp, PORT);
        System.out.println("Connected!\n");

        PrintWriter out = new PrintWriter(
                new OutputStreamWriter(socket.getOutputStream(), "UTF-8"), true);
        BufferedReader in = new BufferedReader(
                new InputStreamReader(socket.getInputStream(), "UTF-8"));

        // ── Listener thread ───────────────────────────────────────────
        // Reads server messages in background without blocking the input loop.
        Thread listener = new Thread(() -> {
            try {
                String line;
                while ((line = in.readLine()) != null) {
                    if (line.isBlank()) continue;
                    Envelope env = JsonUtil.fromJson(line);
                    printIncoming(env);
                }
            } catch (IOException e) {
                System.out.println("\n[CLIENT] Disconnected from server.");
            }
        }, "listener-thread");
        listener.setDaemon(true); // exits automatically when main thread exits
        listener.start();

        // ── Input loop ────────────────────────────────────────────────
        Scanner scanner = new Scanner(System.in);
        System.out.println("Commands: /register <u> <p>  /login <u> <p>  /pm <u> <msg>");
        System.out.println("          /join <grp>  /create <grp>  /group <grp> <msg>  /quit\n");

        while (scanner.hasNextLine()) {
            String input = scanner.nextLine().strip();
            if (input.isEmpty()) continue;

            if (input.startsWith("/register ")) {
                String[] p = input.split(" ", 3);
                if (p.length < 3) { System.out.println("Usage: /register <user> <pass>"); continue; }
                out.println(JsonUtil.toJson(JsonUtil.envelope(
                        MessageType.REGISTER, "username", p[1], "password", p[2])));

            } else if (input.startsWith("/login ")) {
                String[] p = input.split(" ", 3);
                if (p.length < 3) { System.out.println("Usage: /login <user> <pass>"); continue; }
                out.println(JsonUtil.toJson(JsonUtil.envelope(
                        MessageType.LOGIN, "username", p[1], "password", p[2])));

            } else if (input.startsWith("/pm ")) {
                // /pm alice hello there
                String[] p = input.split(" ", 3);
                if (p.length < 3) { System.out.println("Usage: /pm <user> <msg>"); continue; }
                String encrypted = AesUtil.encrypt(p[2]);
                out.println(JsonUtil.toJson(JsonUtil.envelope(
                        MessageType.PRIVATE_MSG, "to", p[1], "text", encrypted)));

            } else if (input.startsWith("/group ")) {
                // /group team-a standup in 5
                String[] p = input.split(" ", 3);
                if (p.length < 3) { System.out.println("Usage: /group <grp> <msg>"); continue; }
                String encrypted = AesUtil.encrypt(p[2]);
                out.println(JsonUtil.toJson(JsonUtil.envelope(
                        MessageType.GROUP_MSG, "groupName", p[1], "text", encrypted)));

            } else if (input.startsWith("/join ")) {
                String grp = input.substring(6).strip();
                out.println(JsonUtil.toJson(JsonUtil.envelope(MessageType.JOIN_GROUP, "groupName", grp)));

            } else if (input.startsWith("/create ")) {
                String grp = input.substring(8).strip();
                out.println(JsonUtil.toJson(JsonUtil.envelope(MessageType.CREATE_GROUP, "groupName", grp)));

            } else if (input.equalsIgnoreCase("/quit")) {
                System.out.println("Goodbye.");
                break;

            } else {
                // Plain text → broadcast (AES-encrypted)
                String encrypted = AesUtil.encrypt(input);
                out.println(JsonUtil.toJson(JsonUtil.envelope(MessageType.BROADCAST, "text", encrypted)));
            }
        }

        socket.close();
    }

    // ── Incoming message handler ──────────────────────────────────────

    private static void printIncoming(Envelope env) {
        switch (env.type) {
            case AUTH_SUCCESS -> System.out.println(
                    "✓ Logged in as: " + env.payload.get("username").asText());

            case AUTH_FAILURE -> System.out.println(
                    "✗ Login failed: " + env.payload.get("reason").asText());

            case BROADCAST -> {
                String from = env.payload.get("from").asText();
                String raw  = env.payload.get("text").asText();
                String text = tryDecrypt(raw);
                System.out.println("[ALL] " + from + ": " + text);
            }

            case PRIVATE_MSG -> {
                String from = env.payload.get("from").asText();
                String raw  = env.payload.get("text").asText();
                String text = tryDecrypt(raw);
                System.out.println("[DM from " + from + "] " + text);
            }

            case GROUP_MSG -> {
                String from  = env.payload.get("from").asText();
                String group = env.payload.get("groupName").asText();
                String raw   = env.payload.get("text").asText();
                String text  = tryDecrypt(raw);
                System.out.println("[" + group + "] " + from + ": " + text);
            }

            case USER_JOINED -> System.out.println(
                    "→ " + env.payload.get("username").asText() + " joined");

            case USER_LEFT -> System.out.println(
                    "← " + env.payload.get("username").asText() + " left");

            case USER_LIST -> System.out.println(
                    "Online: " + env.payload.get("users"));

            case GROUP_LIST -> System.out.println(
                    "[GROUP] " + env.payload.get("action").asText()
                            + ": " + env.payload.get("groupName").asText());

            case FILE_OFFER -> System.out.println(
                    "[FILE] Incoming file from " + env.payload.get("from").asText()
                            + ": " + env.payload.get("fileName").asText()
                            + " (" + env.payload.get("fileSize").asLong() + " bytes)");

            case VOICE_CALL_OFFER -> System.out.println(
                    "[VOICE] Incoming call from " + env.payload.get("from").asText());

            case VOICE_CALL_ACCEPT -> System.out.println(
                    "[VOICE] Call accepted by " + env.payload.get("from").asText());

            case VOICE_CALL_REJECT -> System.out.println(
                    "[VOICE] Call rejected by " + env.payload.get("from").asText());

            case VOICE_CALL_HANGUP -> System.out.println(
                    "[VOICE] Call ended.");

            case ERROR -> System.out.println(
                    "[ERROR] " + env.payload.get("code").asText()
                            + " — " + env.payload.get("message").asText());

            case PONG -> {}  // keep-alive reply — no output needed

            case HISTORY_RESPONSE -> {
                String channel = env.payload.get("channel").asText();
                var msgs = env.payload.get("messages");
                System.out.println("──── HISTORY: " + channel.toUpperCase() + " ────");
                if (msgs != null && msgs.isArray()) {
                    msgs.forEach(m -> {
                        String raw  = m.get("content").asText();
                        String text = tryDecrypt(raw);
                        System.out.println("[" + m.get("sender").asText() + "] " + text);
                    });
                }
                System.out.println("──── LIVE ────");
            }

            default -> System.out.println("[" + env.type + "] " + env.payload);
        }
    }

    /** Attempt AES decrypt; fall back to raw text if the message isn't encrypted. */
    private static String tryDecrypt(String raw) {
        try {
            return AesUtil.decrypt(raw);
        } catch (Exception e) {
            return raw; // plain-text fallback (e.g. SYSTEM messages from server)
        }
    }
}
