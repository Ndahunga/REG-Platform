package rw.terimbere.csams.modules.payout.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import rw.terimbere.csams.modules.payout.entity.PayoutRunStatus;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PayoutRunResponse {

    private UUID id;
    private UUID cooperativeId;
    private String name;
    private LocalDate periodFrom;
    private LocalDate periodTo;
    private boolean includeRegular;
    private boolean includeSpecial;
    private BigDecimal availableFundSnapshot;
    private BigDecimal payoutPoolAmount;
    private BigDecimal totalEligibleContributions;
    private String currency;
    private PayoutRunStatus status;
    private Instant confirmedAt;
    private UUID confirmedBy;
    private Instant paidAt;
    private UUID paidBy;
    private UUID createdBy;
    private String notes;
    private Instant createdAt;
    private Instant updatedAt;
    private List<PayoutLineResponse> lines;
}
