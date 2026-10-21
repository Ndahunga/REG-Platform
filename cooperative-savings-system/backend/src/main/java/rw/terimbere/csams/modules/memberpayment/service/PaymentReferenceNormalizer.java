package rw.terimbere.csams.modules.memberpayment.service;

import java.util.Locale;
import org.springframework.util.StringUtils;
import rw.terimbere.csams.modules.memberpayment.entity.PaymentMethod;
import rw.terimbere.csams.shared.exceptions.BusinessException;

/**
 * Turns a user-typed transaction reference into the form used for duplicate detection.
 *
 * <p>Normalization is intentionally conservative: it removes only presentation noise (case, whitespace,
 * punctuation). It never strips leading zeros, never truncates and never drops ASCII letters or digits, because two different
 * transactions must not be merged by accident. The result is unique only per Saving Scheme and payment method,
 * never globally.
 */
public final class PaymentReferenceNormalizer {

    public static final int MAX_REFERENCE_LENGTH = 128;

    private PaymentReferenceNormalizer() {}

    /** Returns {@code null} when no reference was supplied; throws when one was supplied but is unusable. */
    public static String normalize(String reference) {
        if (!StringUtils.hasText(reference)) {
            return null;
        }
        String trimmed = reference.trim();
        if (trimmed.length() > MAX_REFERENCE_LENGTH) {
            throw new BusinessException(
                    "INVALID_PAYMENT_REFERENCE",
                    "Transaction reference must be at most " + MAX_REFERENCE_LENGTH + " characters");
        }
        StringBuilder out = new StringBuilder(trimmed.length());
        for (char c : trimmed.toUpperCase(Locale.ROOT).toCharArray()) {
            if ((c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')) {
                out.append(c);
            }
        }
        if (out.isEmpty()) {
            throw new BusinessException(
                    "INVALID_PAYMENT_REFERENCE", "Transaction reference must contain letters or digits");
        }
        return out.toString();
    }

    /** Duplicate-detection key: the same reference text under another method is a different key. */
    public static String key(PaymentMethod method, String normalizedReference) {
        return normalizedReference == null ? null : method.name() + "|" + normalizedReference;
    }
}
