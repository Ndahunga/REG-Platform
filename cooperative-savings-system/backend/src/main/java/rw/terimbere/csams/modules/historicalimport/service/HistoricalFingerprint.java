package rw.terimbere.csams.modules.historicalimport.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.UUID;

final class HistoricalFingerprint {

    private HistoricalFingerprint() {}

    static String sha256Hex(byte[] bytes) {
        return hex(digest().digest(bytes));
    }

    static String of(UUID cooperativeId, String domain, String... parts) {
        StringBuilder builder = new StringBuilder();
        builder.append(cooperativeId).append('|').append(domain);
        if (parts != null) {
            for (String part : parts) {
                builder.append('|').append(normalize(part));
            }
        }
        return sha256Hex(builder.toString().getBytes(StandardCharsets.UTF_8));
    }

    static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is required", ex);
        }
    }

    private static String hex(byte[] bytes) {
        return HexFormat.of().formatHex(bytes);
    }
}
