package com.lanchat.client;

import java.io.*;
import java.net.Socket;
import java.net.SocketException;

/**
 * Manages the TCP socket lifecycle for the chat client.
 *
 * Responsibilities:
 *   - Initial connection to the server
 *   - Exposing PrintWriter / BufferedReader to MessageSender / MessageListener
 *   - Auto-reconnect with exponential back-off (up to MAX_BACKOFF_MS)
 *   - Clean disconnect / shutdown
 *
 * Android note: This class is used identically in both the desktop console
 * client and the Android client. On Android, call connect() from a background
 * thread (e.g. a ViewModel coroutine or a dedicated Thread) — never on the
 * main/UI thread.
 *
 * Thread safety: connect() and disconnect() are synchronized. getWriter() and
 * getReader() return the current socket's streams; callers must not use them
 * after disconnect() has been called.
 */
public class ConnectionManager {

    private static final int    INITIAL_BACKOFF_MS = 1_000;   // 1 s
    private static final int    MAX_BACKOFF_MS     = 30_000;  // 30 s
    private static final int    SO_TIMEOUT_MS      = 0;       // 0 = block forever

    private final String host;
    private final int    port;

    private Socket        socket;
    private PrintWriter   writer;
    private BufferedReader reader;
    private volatile boolean shouldReconnect = false;

    public ConnectionManager(String host, int port) {
        this.host = host;
        this.port = port;
    }

    // ── Connect ───────────────────────────────────────────────────────

    /**
     * Open a TCP connection to the server.
     * Blocks until the connection succeeds (with back-off retries if requested).
     *
     * @param withRetry  if true, retries indefinitely with exponential back-off
     * @throws IOException if withRetry=false and the connection fails
     */
    public synchronized void connect(boolean withRetry) throws IOException {
        int backoff = INITIAL_BACKOFF_MS;
        while (true) {
            try {
                socket = new Socket(host, port);
                socket.setSoTimeout(SO_TIMEOUT_MS);
                socket.setKeepAlive(true);      // OS-level TCP keep-alive
                socket.setTcpNoDelay(true);     // no Nagle — send small packets immediately

                writer = new PrintWriter(
                        new OutputStreamWriter(socket.getOutputStream(), "UTF-8"), true);
                reader = new BufferedReader(
                        new InputStreamReader(socket.getInputStream(), "UTF-8"));

                System.out.println("[CONNECTION] Connected to " + host + ":" + port);
                shouldReconnect = true;
                return; // success

            } catch (IOException e) {
                if (!withRetry) throw e;
                System.out.println("[CONNECTION] Failed to connect. Retrying in "
                        + (backoff / 1000) + "s... (" + e.getMessage() + ")");
                try { Thread.sleep(backoff); } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Connection interrupted", ie);
                }
                backoff = Math.min(backoff * 2, MAX_BACKOFF_MS);
            }
        }
    }

    // ── Reconnect ────────────────────────────────────────────────────

    /**
     * Called by MessageListener when readLine() returns null (server closed connection).
     * Silently closes the old socket and opens a new one with back-off.
     * After reconnect, the caller must re-authenticate (session tokens don't survive).
     */
    public void reconnect() {
        if (!shouldReconnect) return;
        System.out.println("[CONNECTION] Lost connection. Attempting to reconnect...");
        closeQuietly();
        try {
            connect(true); // retry forever
        } catch (IOException e) {
            // Only thrown if withRetry=false — won't happen here
            System.err.println("[CONNECTION] Reconnect failed: " + e.getMessage());
        }
    }

    // ── Disconnect ───────────────────────────────────────────────────

    /** Clean shutdown — no reconnect attempt after this. */
    public synchronized void disconnect() {
        shouldReconnect = false;
        closeQuietly();
        System.out.println("[CONNECTION] Disconnected.");
    }

    private void closeQuietly() {
        try {
            if (socket != null && !socket.isClosed()) socket.close();
        } catch (IOException ignored) {}
        socket = null;
        writer = null;
        reader = null;
    }

    // ── Accessors ─────────────────────────────────────────────────────

    public PrintWriter    getWriter() { return writer; }
    public BufferedReader getReader() { return reader; }
    public boolean        isConnected() {
        return socket != null && socket.isConnected() && !socket.isClosed();
    }
    public String getHost() { return host; }
    public int    getPort() { return port; }
}
