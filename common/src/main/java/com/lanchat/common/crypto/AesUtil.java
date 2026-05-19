package com.lanchat.common.crypto;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * AES-256-CBC encryption — optimised for low latency on Android.
 *
 * Performance improvements over the original:
 *   - Single static SecureRandom (SecureRandom init is 20-100ms on Android)
 *   - ThreadLocal Cipher instances (avoid JCE lookup on every message)
 *   - Key derived once at class load time
 *
 * Wire format: base64(IV) + ":" + base64(ciphertext)
 */
public final class AesUtil {

    private static final String ALGORITHM = "AES/CBC/PKCS5Padding";
    private static final int    IV_SIZE   = 16;

    private static final String        PASSPHRASE  = "LanChatSecretKey2024!@#";
    private static final SecretKeySpec SECRET_KEY  = deriveKey(PASSPHRASE);

    // Single SecureRandom — thread-safe, much cheaper than creating one per message
    private static final SecureRandom RNG = new SecureRandom();

    // Reuse Cipher instances per thread — avoids JCE provider lookup on every call
    private static final ThreadLocal<Cipher> CIPHER_CACHE = ThreadLocal.withInitial(() -> {
        try { return Cipher.getInstance(ALGORITHM); }
        catch (Exception e) { throw new RuntimeException("Cipher init failed", e); }
    });

    private AesUtil() {}

    public static String encrypt(String plaintext) {
        try {
            byte[] iv = new byte[IV_SIZE];
            RNG.nextBytes(iv);

            Cipher cipher = CIPHER_CACHE.get();
            cipher.init(Cipher.ENCRYPT_MODE, SECRET_KEY, new IvParameterSpec(iv));
            byte[] encrypted = cipher.doFinal(plaintext.getBytes("UTF-8"));

            return Base64.getEncoder().encodeToString(iv)
                    + ":"
                    + Base64.getEncoder().encodeToString(encrypted);
        } catch (Exception e) {
            throw new RuntimeException("AES encryption failed", e);
        }
    }

    public static String decrypt(String encryptedText) {
        try {
            int colon = encryptedText.indexOf(':');
            if (colon < 0) throw new IllegalArgumentException("Invalid format");

            byte[] iv         = Base64.getDecoder().decode(encryptedText.substring(0, colon));
            byte[] ciphertext = Base64.getDecoder().decode(encryptedText.substring(colon + 1));

            Cipher cipher = CIPHER_CACHE.get();
            cipher.init(Cipher.DECRYPT_MODE, SECRET_KEY, new IvParameterSpec(iv));
            return new String(cipher.doFinal(ciphertext), "UTF-8");
        } catch (Exception e) {
            throw new RuntimeException("AES decryption failed", e);
        }
    }

    private static SecretKeySpec deriveKey(String passphrase) {
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            return new SecretKeySpec(sha.digest(passphrase.getBytes("UTF-8")), "AES");
        } catch (Exception e) {
            throw new RuntimeException("AES key derivation failed", e);
        }
    }
}
