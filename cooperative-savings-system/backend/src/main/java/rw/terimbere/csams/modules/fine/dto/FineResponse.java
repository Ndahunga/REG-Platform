package rw.terimbere.csams.modules.fine.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import rw.terimbere.csams.modules.fine.entity.FineCalculationMode;
import rw.terimbere.csams.modules.fine.entity.FineStatus;
import rw.terimbere.csams.modules.fine.entity.FineType;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FineResponse {

    private UUID id;
    private UUID cooperativeId;
    private UUID memberUserId;
    private String memberName;
    private FineType fineType;
    private FineCalculationMode calculationMode;
    private UUID sourceContributionId;
    private Integer contributionYear;
    private Integer contributionMonth;
    private BigDecimal baseAmount;
    private BigDecimal dailyIncrementSnapshot;
    private int overdueDays;
    private BigDecimal totalAmount;
    private BigDecimal paidAmount;
    private BigDecimal outstandingAmount;
    private String reason;
    private String notes;
    private LocalDate issuedDate;
    private LocalDate dueDate;
    private FineStatus status;
    private UUID issuedBy;
    private String currency;
    private Instant createdAt;
    private Instant updatedAt;
}
