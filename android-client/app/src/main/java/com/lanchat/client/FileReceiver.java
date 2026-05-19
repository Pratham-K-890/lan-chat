package com.lanchat.client;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.IntConsumer;

/**
 * Collects incoming FILE_CHUNK packets and reassembles the file on disk.
 *
 * One FileReceiver instance is shared for all concurrent incoming transfers.
 * Each transfer is keyed by transferId (UUID from the sender).
 *
 * Usage in MessageListener.MessageCallback:
 *   onFileOffer  → fileReceiver.onOffer(transferId, fileName, from)
 *                  then decide accept/reject; call sender.sendFileAccept(transferId)
 *   onFileChunk  → fileReceiver.onChunk(transferId, chunkIndex, base64Data)
 *   onFileDone   → fileReceiver.onDone(transferId, progressCallback)
 *
 * Android note: saveDirectory should be something like:
 *   context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS).toPath()
 *   Files.createDirectories() and Files.write() work identically on Android.
 */
public class FileReceiver {

    // transferId → ordered map of chunkIndex → raw bytes
    private final ConcurrentHashMap<String, TreeMap<Integer, byte[]>> buffers
            = new ConcurrentHashMap<>();

    // transferId → original file name (received in FILE_OFFER)
    private final ConcurrentHashMap<String, String> fileNames
            = new ConcurrentHashMap<>();

    // transferId → sender username
    private final ConcurrentHashMap<String, String> senders
            = new ConcurrentHashMap<>();

    private final Path saveDirectory;

    public FileReceiver(Path saveDirectory) {
        this.saveDirectory = saveDirectory;
    }

    // ── Called by MessageCallback ─────────────────────────────────────

    /**
     * Prepare buffers for an incoming transfer.
     * Call this when MessageListener fires onFileOffer().
     */
    public void onOffer(String transferId, String fileName, String fromUser) {
        buffers.put(transferId, new TreeMap<>());
        fileNames.put(transferId, fileName);
        senders.put(transferId, fromUser);
        System.out.println("[FileReceiver] Incoming: " + fileName
                + " from " + fromUser + " [" + transferId + "]");
    }

    /**
     * Store a received chunk.
     * Call this when MessageListener fires onFileChunk().
     */
    public void onChunk(String transferId, int chunkIndex, String base64Data) {
        TreeMap<Integer, byte[]> buf = buffers.get(transferId);
        if (buf == null) {
            System.err.println("[FileReceiver] Unknown transfer: " + transferId);
            return;
        }
        byte[] decoded = Base64.getDecoder().decode(base64Data);
        buf.put(chunkIndex, decoded);
    }

    /**
     * Reassemble all chunks and write to disk.
     * Call this when MessageListener fires onFileDone().
     *
     * @param progressCallback  called with percent (0–100) during assembly; may be null
     * @return  Path of the saved file, or null if the transfer was unknown
     */
    public Path onDone(String transferId, IntConsumer progressCallback) throws IOException {
        TreeMap<Integer, byte[]> buf = buffers.remove(transferId);
        String fileName = fileNames.remove(transferId);
        senders.remove(transferId);

        if (buf == null || fileName == null) {
            System.err.println("[FileReceiver] No buffer for transfer: " + transferId);
            return null;
        }

        // Reassemble in ascending chunk order
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        int total = buf.size();
        int done  = 0;
        for (byte[] chunk : buf.values()) {
            baos.write(chunk);
            done++;
            if (progressCallback != null) {
                progressCallback.accept((int) ((done * 100.0) / total));
            }
        }

        Files.createDirectories(saveDirectory);
        Path outPath = saveDirectory.resolve(fileName);
        Files.write(outPath, baos.toByteArray());

        System.out.println("[FileReceiver] Saved: " + outPath.toAbsolutePath());
        return outPath;
    }

    /** Returns the file name for a pending transfer, or null if unknown. */
    public String getFileName(String transferId) {
        return fileNames.get(transferId);
    }

    /** Returns the sender username for a pending transfer, or null if unknown. */
    public String getSender(String transferId) {
        return senders.get(transferId);
    }
}
