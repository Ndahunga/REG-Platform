package rw.terimbere.csams.modules.report.service;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.Map;
import rw.terimbere.csams.modules.report.dto.ReportExportRequest;
import rw.terimbere.csams.shared.exceptions.ValidationException;

/**
 * Report export date rules: required from/to timeline and no future dates.
 * Historical imported activity before CSAMS onboarding must remain visible, so
 * registration date and a fixed multi-year cap are not applied.
 */
public final class ReportTimelineValidator {

    public static final ZoneId ZONE = ZoneId.of("Africa/Kigali");
    public static final int MIN_YEAR = 2000;

    private ReportTimelineValidator() {}

    public static void validate(ReportExportRequest request, LocalDate today, LocalDate registrationDate) {
        Map<String, String> errors = new LinkedHashMap<>();
        LocalDate from = request.getFromDate();
        LocalDate to = request.getToDate();

        if (from == null) {
            errors.put("fromDate", "fromDate is required");
        }
        if (to == null) {
            errors.put("toDate", "toDate is required");
        }
        if (from != null && from.isAfter(today)) {
            errors.put("fromDate", "fromDate cannot be in the future");
        }
        if (to != null && to.isAfter(today)) {
            errors.put("toDate", "toDate cannot be in the future");
        }
        if (from != null && to != null && to.isBefore(from)) {
            errors.put("toDate", "toDate must be on or after fromDate");
        }

        Integer year = request.getYear();
        Integer month = request.getMonth();
        if (year == null && month != null) {
            errors.put("year", "year and month must both be provided or both omitted");
        }
        if (year != null && month == null) {
            errors.put("month", "year and month must both be provided or both omitted");
        }
        if (year != null) {
            if (year < MIN_YEAR) {
                errors.put("year", "year must be " + MIN_YEAR + " or later");
            }
            if (year > today.getYear()) {
                errors.put("year", "year cannot be in the future");
            }
        }
        if (month != null && (month < 1 || month > 12)) {
            errors.put("month", "month must be between 1 and 12");
        }
        if (year != null && month != null && month >= 1 && month <= 12) {
            YearMonth selected = YearMonth.of(year, month);
            if (selected.isAfter(YearMonth.from(today))) {
                errors.put("month", "year/month cannot be in the future");
            }
        }

        if (!errors.isEmpty()) {
            throw new ValidationException("Invalid report timeline", errors);
        }
    }
}
