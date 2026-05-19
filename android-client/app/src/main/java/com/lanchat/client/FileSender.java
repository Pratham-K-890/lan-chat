package com.lanchat.client;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Base64;
import java.util.UUID;
import java.util.function.IntConsumer;

/**
 * Sends a file to a remote user in 64 KB base64-encoded chunks over the TCP connection.
 *
 * Flow:
 *   1. sendOffer()  → server forwards FILE_OFFER to receiver
 *   2. Wait for FILE_ACCEPT (MessageListener fires onFileAccept)
 *   3. startSending() → send all chunks then FILE_DONE
 *
 * The wait between offer and accept is handled asynchronously:
 *   - MessageListener.onFileAccept() calls startSending() when the transferId matches.
 *   - On Android: keep a Map<transferId, FileSender> in your ViewModel; look it up in onFileAccept.
 *
 * Android note: Call startSending() from a background coroutine / Executors thread.
 *   Large files (>10 MB) should show a notification or progress dialog.
 *   Use progressCallback to update a ProgressBar on the UI thread via runOnUiThread().
 */
public class FileSender {

    private static final int CHUNK_SIZE = 64 * 1024; // 64 KB per chunk

    private final MessageSender sender;
    private final String        targetUser;
    private final Path          filePath;
    private final String        transferId;

    /**
     * @param sender      the MessageSender connected to the server
     * @param targetUser  username of the intended recipient
     * @param filePath    local file to send
     */
    public FileSender(MessageSender sender, String targetUser, Path filePath) {
        this.sender     = sender;
        this.targetUser = targetUser;
        this.filePath   = filePath;
        this.transferId = UUID.randomUUID().toString();
    }

    public String getTransferId() { return transferId; }

    // ── Step 1: Offer ────────────────────────────────────────────────

    /**
     * Send FILE_OFFER to the server.
     * Call this first; then wait for onFileAccept() from MessageListener.
     */
    public void sendOffer() throws IOException {
        long fileSize = Files.size(filePath);
        String fileName = filePath.getFileName().toString();
        sender.sendFileOffer(targetUser, fileName, fileSize, transferId);
        System.out.println("[FileSender] Offer sent: " + fileName
                + " (" + fileSize + " bytes) → " + targetUser);
    }

    // ── Step 2: Send chunks ──────────────────────────────────────────

    /**
     * Read the file, encode chunks as base64, send each as FILE_CHUNK, then FILE_DONE.
     *
     * @param progressCallback  called with percent (0–100) after each chunk; may be null
     * @throws IOException if the file cannot be read
     */
    public void startSending(IntConsumer progressCallback) throws IOException {
        byte[] fileBytes  = Files.readAllBytes(filePath);
        int    totalChunks = (int) Math.ceil((double) fileBytes.length / CHUNK_SIZE);

        System.out.println("[FileSender] Sending " + totalChunks
                + " chunk(s) for transfer " + transferId);

        for (int i = 0; i < totalChunks; i++) {
            int start   = i * CHUNK_SIZE;
            int end     = Math.min(start + CHUNK_SIZE, fileBytes.length);
            byte[] chunk = Arrays.copyOfRange(fileBytes, start, end);
            String b64   = Base64.getEncoder().encodeToString(chunk);

            sender.sendFileChunk(transferId, i, b64);

            if (progressCallback != null) {
                int pct = (int) ((i + 1.0) / totalChunks * 100);
                progressCallback.accept(pct);
            }
        }

        sender.sendFileDone(transferId);
        System.out.println("[FileSender] Done: " + filePath.getFileName());
    }

    /** Convenience: offer then immediately send (no accept handshake). Use for testing only. */
    public void offerAndSend(IntConsumer progressCallback) throws IOException {
        sendOffer();
        startSending(progressCallback);
    }
}
