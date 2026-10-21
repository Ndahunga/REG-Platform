package rw.terimbere.csams.modules.report.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
public class ReportWhatsAppShareRequest extends ReportExportRequest {

    @NotBlank
    private String recipientPhone;
}
