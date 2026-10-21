package rw.terimbere.csams.shared.utilities;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.Map;
import rw.terimbere.csams.shared.exceptions.ValidationException;

/** Optional from/to list-filter rules: empty is allowed; inverted or future dates are not. */
public final class DateRangeValidator {

    public static final ZoneId ZONE = ZoneId.of("Africa/Kigali");

    private DateRangeValidator() {}

    public static void validateOptional(LocalDate from, LocalDate to) {
        validateOptional(from, to, LocalDate.now(ZONE));
    }

    public static void validateOptional(LocalDate from, LocalDate to, LocalDate today) {
        Map<String, String> errors = new LinkedHashMap<>();
        if (from != null && from.isAfter(today)) {
            errors.put("from", "from date cannot be in the future");
        }
        if (to != null && to.isAfter(today)) {
            errors.put("to", "to date cannot be in the future");
        }
        if (from != null && to != null && to.isBefore(from)) {
            errors.put("to", "to date cannot be before from date");
        }
        if (!errors.isEmpty()) {
            throw new ValidationException("Invalid date range", errors);
        }
    }
}
