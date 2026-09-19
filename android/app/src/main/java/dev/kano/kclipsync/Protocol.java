package dev.kano.kclipsync;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** Constants and helpers for the plaintext v1 wire protocol. */
public final class Protocol {
    public static final int VERSION = 1;
    public static final int DEFAULT_PORT = 47631;
    public static final int DEFAULT_MAX_BYTES = 1024 * 1024;
    public static final int FRAME_OVERHEAD = 5;
    public static final int T_HELLO = 1;
    public static final int T_CLIP = 2;
    public static final int T_PING = 3;
    public static final int T_PONG = 4;
    public static final int T_BYE = 15;

    private Protocol() {}

    public static String normalizeText(String text) {
        return text.replace("\r\n", "\n").replace('\r', '\n');
    }

    public static String sha256Hex(String text) {
        return sha256Hex(text.getBytes(StandardCharsets.UTF_8));
    }

    public static String sha256Hex(byte[] data) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(data);
            StringBuilder out = new StringBuilder(hash.length * 2);
            for (byte b : hash) out.append(String.format("%02x", b & 0xff));
            return out.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    public static int maxFrame(int maxBytes) {
        return Math.max(64 * 1024, maxBytes * 3);
    }
}
