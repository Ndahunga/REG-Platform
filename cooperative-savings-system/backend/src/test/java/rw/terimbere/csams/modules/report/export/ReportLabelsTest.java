package rw.terimbere.csams.modules.report.export;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ReportLabelsTest {

    @Test
    void mapsStoredCooperativeEntityTypeForDocuments() {
        assertThat(ReportLabels.entityType("Cooperative")).isEqualTo("Saving Scheme");
        assertThat(ReportLabels.entityType("Loan")).isEqualTo("Loan");
        assertThat(ReportLabels.entityType(null)).isEqualTo("");
    }
}
