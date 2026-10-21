package rw.terimbere.csams.shared.validation;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Cooperative create/update field rules for Rwanda (phone, email, RCA-style registration).
 */
public final class CooperativeFieldRules {

    public static final String CURRENCY_RWF = "RWF";
    public static final ZoneId ZONE = ZoneId.of("Africa/Kigali");
    public static final LocalDate MIN_REGISTRATION_DATE = LocalDate.of(1950, 1, 1);
    public static final int MIN_DUE_DAY = 1;
    public static final int MAX_DUE_DAY = 28;

    private static final Pattern EMAIL =
            Pattern.compile("^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$");
    private static final Pattern RWANDA_MOBILE = Pattern.compile("^07\\d{8}$");
    private static final Pattern REGISTRATION_NUMBER =
            Pattern.compile("^[A-Za-z0-9]+(?:[/\\-][A-Za-z0-9]+)*$");

    private CooperativeFieldRules() {}

    public static String normalizePhone(String raw) {
        if (raw == null) {
            return null;
        }
        String digits = raw.trim().replaceAll("[\\s().-]", "");
        if (digits.startsWith("+")) {
            digits = digits.substring(1);
        }
        if (digits.startsWith("250") && digits.length() == 12) {
            digits = "0" + digits.substring(3);
        } else if (digits.length() == 9 && digits.startsWith("7")) {
            digits = "0" + digits;
        }
        return digits;
    }

    public static boolean isValidRwandanPhone(String raw) {
        String normalized = normalizePhone(raw);
        return normalized != null && RWANDA_MOBILE.matcher(normalized).matches();
    }

    public static boolean isValidEmail(String raw) {
        if (raw == null || raw.isBlank()) {
            return false;
        }
        String value = raw.trim();
        return value.length() <= 255 && EMAIL.matcher(value).matches();
    }

    public static String normalizeRegistrationNumber(String raw) {
        if (raw == null) {
            return null;
        }
        String value = raw.trim().replaceAll("\\s+", "");
        return value.isEmpty() ? null : value.toUpperCase(Locale.ROOT);
    }

    public static boolean isValidRegistrationNumber(String raw) {
        String value = normalizeRegistrationNumber(raw);
        if (value == null || value.length() < 4 || value.length() > 32) {
            return false;
        }
        return REGISTRATION_NUMBER.matcher(value).matches();
    }

    public static boolean isValidRegistrationDate(LocalDate date) {
        if (date == null) {
            return false;
        }
        LocalDate today = LocalDate.now(ZONE);
        return !date.isBefore(MIN_REGISTRATION_DATE) && !date.isAfter(today);
    }

    public static boolean isValidDueDay(Integer day) {
        return day != null && day >= MIN_DUE_DAY && day <= MAX_DUE_DAY;
    }
}
