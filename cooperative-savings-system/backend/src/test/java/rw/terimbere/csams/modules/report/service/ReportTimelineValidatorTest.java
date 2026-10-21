package rw.terimbere.csams.modules.report.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import rw.terimbere.csams.modules.report.dto.ReportExportRequest;
import rw.terimbere.csams.modules.report.dto.ReportType;
import rw.terimbere.csams.shared.exceptions.ValidationException;

class ReportTimelineValidatorTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 8, 20);

    @Test
    void acceptsValidTimeline() {
        assertDoesNotThrow(() -> ReportTimelineValidator.validate(request("2026-01-01", "2026-08-20"), TODAY, null));
    }

    @Test
    void rejectsMissingDates() {
        assertThatThrownBy(() -> ReportTimelineValidator.validate(request(null, null), TODAY, null))
                .isInstanceOf(ValidationException.class)
                .hasMessage("Invalid report timeline");
    }

    @Test
    void rejectsFutureDates() {
        assertThatThrownBy(() -> ReportTimelineValidator.validate(request("2026-08-21", "2026-08-21"), TODAY, null))
                .isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> ReportTimelineValidator.validate(request("2026-01-01", "2026-08-21"), TODAY, null))
                .isInstanceOf(ValidationException.class);
    }

    @Test
    void rejectsFromAfterTo() {
        assertThatThrownBy(() -> ReportTimelineValidator.validate(request("2026-08-20", "2026-01-01"), TODAY, null))
                .isInstanceOf(ValidationException.class);
    }

    @Test
    void acceptsRangeLongerThanFiveYears() {
        assertDoesNotThrow(() -> ReportTimelineValidator.validate(request("2020-01-01", "2026-01-02"), TODAY, null));
    }

    @Test
    void acceptsFromBeforeRegistrationDate() {
        assertDoesNotThrow(() -> ReportTimelineValidator.validate(
                request("2022-01-01", "2026-08-20"), TODAY, LocalDate.of(2024, 1, 15)));
    }

    @Test
    void rejectsFutureYearMonth() {
        ReportExportRequest request = request("2026-01-01", "2026-08-20");
        request.setYear(2026);
        request.setMonth(9);
        assertThatThrownBy(() -> ReportTimelineValidator.validate(request, TODAY, null))
                .isInstanceOf(ValidationException.class);
    }

    @Test
    void acceptsCurrentMonthYear() {
        ReportExportRequest request = request("2026-01-01", "2026-08-20");
        request.setYear(2026);
        request.setMonth(8);
        assertDoesNotThrow(() -> ReportTimelineValidator.validate(request, TODAY, null));
    }

    private static ReportExportRequest request(String from, String to) {
        return ReportExportRequest.builder()
                .reportType(ReportType.CONTRIBUTIONS)
                .fromDate(from == null ? null : LocalDate.parse(from))
                .toDate(to == null ? null : LocalDate.parse(to))
                .build();
    }
}
