package com.lanchat.server;

import com.lanchat.server.db.Database;
import com.lanchat.server.service.GroupService;
import com.lanchat.server.service.UserService;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * LAN Chat Server — entry point.
 *
 * Supports two modes:
 *   Standalone : call main()         — PC / Termux
 *   Embedded   : call startEmbedded() — runs inside the Android app
 *
 * Startup sequence (both modes):
 *   1. Database.init()        — open / create lanchat.db, create tables
 *   2. GroupService.loadFromDb() — rebuild in-memory group map from DB
 *   3. Accept loop            — handle clients until stop() is called
 *   4. Database.close()       — flush WAL and close connection on exit
 */
public class ChatServer {

    public static final int PORT        = 9090;
    public static final int MAX_CLIENTS = 50;

    /** Global routing table: username → live ClientHandler. */
    public static final ConcurrentHashMap<String, ClientHandler> onlineUsers
            = new ConcurrentHashMap<>();

    private static final AtomicBoolean   running      = new AtomicBoolean(false);
    private static volatile ServerSocket serverSocket = null;
    private static volatile ExecutorService pool      = null;

    // ── Standalone (PC / Termux) ──────────────────────────────────────

    public static void main(String[] args) throws IOException {
        System.out.println("╔═══════════════════════════════════════╗");
        System.out.println("║       LAN Chat Server  v2.0           ║");
        System.out.println("╚═══════════════════════════════════════╝");

        // Graceful shutdown hook
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("[SERVER] Shutdown signal received.");
            stop();
        }, "shutdown-hook"));

        boot();
        startAcceptLoop(new ServerSocket(PORT), Executors.newFixedThreadPool(MAX_CLIENTS));
    }

    // ── Embedded (Android) ────────────────────────────────────────────

    /**
     * Start the embedded server. Blocks until stop() is called.
     * Call from a dedicated background thread — never the UI thread.
     */
    public static void startEmbedded() throws IOException {
        if (running.get()) return;

        onlineUsers.clear();
        boot();

        pool         = Executors.newFixedThreadPool(MAX_CLIENTS);
        serverSocket = new ServerSocket(PORT);
        running.set(true);

        System.out.println("[SERVER] Embedded server started on port " + PORT);
        startAcceptLoop(serverSocket, pool);
    }

    /** Stop the embedded server cleanly. */
    public static void stop() {
        if (!running.getAndSet(false)) {
            // Also close DB on standalone shutdown hook
            Database.close();
            return;
        }

        onlineUsers.clear();

        try {
            if (serverSocket != null) serverSocket.close();
        } catch (IOException ignored) {}

        if (pool != null) {
            pool.shutdownNow();
            pool = null;
        }
        serverSocket = null;

        Database.close();
        System.out.println("[SERVER] Stopped.");
    }

    public static boolean isRunning() { return running.get(); }

    // ── Shared boot sequence ──────────────────────────────────────────

    private static void boot() {
        // Clear any stale in-memory state from previous session
        UserService.clearAll();

        // Open database (stub on Android, real SQLite on PC/server)
        Database.init();

        // Restore persisted groups
        GroupService.loadFromDb();

        System.out.println("[SERVER] Ready on TCP port " + PORT);
    }

    // ── Accept loop ───────────────────────────────────────────────────

    private static void startAcceptLoop(ServerSocket ss, ExecutorService threadPool) {
        try {
            while (running.get() || !ss.isClosed()) {
                try {
                    Socket clientSocket = ss.accept();
                    String ip = clientSocket.getInetAddress().getHostAddress();
                    System.out.println("[SERVER] New connection: " + ip);
                    threadPool.execute(new ClientHandler(clientSocket, ip));
                } catch (IOException e) {
                    if (!running.get()) break;
                    System.err.println("[SERVER] Accept error: " + e.getMessage());
                }
            }
        } finally {
            System.out.println("[SERVER] Accept loop exited.");
        }
    }
}
