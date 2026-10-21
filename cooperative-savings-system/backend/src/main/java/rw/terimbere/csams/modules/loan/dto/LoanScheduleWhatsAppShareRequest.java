package rw.terimbere.csams.modules.loan.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class LoanScheduleWhatsAppShareRequest {

    @NotBlank
    private String recipientPhone;
}
