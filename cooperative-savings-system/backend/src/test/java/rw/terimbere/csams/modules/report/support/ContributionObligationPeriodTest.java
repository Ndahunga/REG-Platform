package rw.terimbere.csams.modules.report.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.time.YearMonth;
import org.junit.jupiter.api.Test;
import rw.terimbere.csams.modules.report.dto.ReportExportRequest;
import rw.terimbere.csams.modules.report.dto.ReportType;
import rw.terimbere.csams.shared.exceptions.ValidationException;

class ContributionObligationPeriodTest {

    @Test
    void resolvesMultiMonthRangeAcrossYearBoundary() {
        ReportExportRequest request = ReportExportRequest.builder()
                .reportType(ReportType.FULL_FINANCIAL)
                .fromDate(LocalDate.of(2025, 11, 1))
                .toDate(LocalDate.of(2026, 2, 28))
                .build();

        ContributionObligationPeriod.Range range = ContributionObligationPeriod.resolve(request);

        assertThat(range.from()).isEqualTo(YearMonth.of(2025, 11));
        assertThat(range.to()).isEqualTo(YearMonth.of(2026, 2));
        assertThat(range.singleMonth()).isFalse();
        assertThat(range.selectedMonthSpan()).isEqualTo(4);
    }

    @Test
    void singleMonthWhenFromAndToSameYearMonth() {
        ReportExportRequest request = ReportExportRequest.builder()
                .reportType(ReportType.FULL_FINANCIAL)
                .fromDate(LocalDate.of(2026, 4, 1))
                .toDate(LocalDate.of(2026, 4, 30))
                .build();

        ContributionObligationPeriod.Range range = ContributionObligationPeriod.resolve(request);

        assertThat(range.singleMonth()).isTrue();
        assertThat(range.singleMonthLabel(java.util.Locale.ENGLISH)).isEqualTo("April 2026");
    }

    @Test
    void explicitYearMonthForcesSingleMonth() {
        ReportExportRequest request = ReportExportRequest.builder()
                .reportType(ReportType.FULL_FINANCIAL)
                .fromDate(LocalDate.of(2026, 1, 1))
                .toDate(LocalDate.of(2026, 7, 31))
                .year(2026)
                .month(3)
                .build();

        ContributionObligationPeriod.Range range = ContributionObligationPeriod.resolve(request);

        assertThat(range.singleMonth()).isTrue();
        assertThat(range.from()).isEqualTo(YearMonth.of(2026, 3));
    }

    @Test
    void rejectsMissingDates() {
        ReportExportRequest request = ReportExportRequest.builder()
                .reportType(ReportType.FULL_FINANCIAL)
                .build();
        assertThatThrownBy(() -> ContributionObligationPeriod.resolve(request))
                .isInstanceOf(ValidationException.class);
    }
}
