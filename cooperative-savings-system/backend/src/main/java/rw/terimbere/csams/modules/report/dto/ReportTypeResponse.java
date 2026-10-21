package rw.terimbere.csams.modules.report.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReportTypeResponse {

    private String code;
    private String label;
    private boolean requiresAuditRead;
    private boolean selfScoped;
}
