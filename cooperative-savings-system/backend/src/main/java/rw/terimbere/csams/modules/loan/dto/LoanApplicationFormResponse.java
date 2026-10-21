package rw.terimbere.csams.modules.loan.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import rw.terimbere.csams.modules.loan.entity.InterestType;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LoanApplicationFormResponse {

    private UUID cooperativeId;
    private String cooperativeName;
    private String currency;
    private UUID memberUserId;
    private String memberFullName;
    private String username;
    private String email;
    private String phone;
    private String nationalId;
    private String address;
    private LocalDate membershipDate;
    private String membershipStatus;
    private String roleInCooperative;
    private BigDecimal requestedAmount;
    private String purpose;
    private Integer termMonths;
    private BigDecimal interestRatePercent;
    private InterestType interestType;
    private LocalDate requestDate;
    private Instant submittedAt;
    private LoanEligibilityResponse eligibility;
}
