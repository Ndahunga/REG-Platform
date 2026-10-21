package rw.terimbere.csams.modules.report.dto;

import java.time.Instant;
import lombok.Builder;
import lombok.Value;

@Value
@Builder
public class ReportHeaderMeta {
    String cooperativeName;
    String reportTitle;
    String selectedPeriod;
    Instant generatedAt;
    String generatedBy;
    String currency;
}
