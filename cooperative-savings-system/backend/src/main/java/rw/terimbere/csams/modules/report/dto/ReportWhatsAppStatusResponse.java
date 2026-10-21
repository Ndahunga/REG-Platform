package rw.terimbere.csams.modules.report.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReportWhatsAppStatusResponse {

    /** True only when WhatsApp Cloud API credentials are present and sharing is enabled. */
    private boolean configured;
}
