package rw.terimbere.csams.modules.investment.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import rw.terimbere.csams.modules.investment.entity.InvestmentStatus;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class InvestmentResponse {

    private UUID id;
    private UUID cooperativeId;
    private String name;
    private String description;
    private BigDecimal amount;
    private BigDecimal expectedReturnAmount;
    private LocalDate expectedReturnDate;
    private BigDecimal remainingCapital;
    private BigDecimal totalCapitalReturned;
    private BigDecimal totalProfitReturned;
    private InvestmentStatus status;
    private String documentFileKey;
    private Instant activatedAt;
    private Instant completedAt;
    private UUID createdBy;
    private Instant createdAt;
    private Instant updatedAt;
}
