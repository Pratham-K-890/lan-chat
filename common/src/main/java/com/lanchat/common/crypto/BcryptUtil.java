package com.lanchat.common.crypto;

import org.mindrot.jbcrypt.BCrypt;

/**
 * Thin wrapper around jBCrypt for password hashing and verification.
 *
 * Used ONLY on the server side (Android client never hashes passwords locally).
 * Included in common so the JAR is self-contained, but Android never calls it.
 *
 * Work factor 12 = ~250ms per hash on a modern CPU.
 * Increase to 13–14 when hardware improves; don't go below 10.
 */
public final class BcryptUtil {

    private static final int WORK_FACTOR = 12;

    private BcryptUtil() {}

    /**
     * Hash a plain-text password.
     * @return bcrypt hash string — store this, never the plain password.
     */
    public static String hash(String password) {
        return BCrypt.hashpw(password, BCrypt.gensalt(WORK_FACTOR));
    }

    /**
     * Verify a plain-text password against a stored bcrypt hash.
     * @return true if the password matches the hash.
     */
    public static boolean verify(String password, String storedHash) {
        try {
            return BCrypt.checkpw(password, storedHash);
        } catch (IllegalArgumentException e) {
            return false; // malformed hash input — treat as mismatch
        }
    }
}
