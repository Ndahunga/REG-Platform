package rw.terimbere.csams.modules.report.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReportWhatsAppShareResponse {

    private boolean sent;
    private String recipient;
    private String filename;
}
