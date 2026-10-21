package rw.terimbere.csams.modules.investment.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class InvestmentReturnResponse {

    private UUID id;
    private UUID investmentId;
    private UUID cooperativeId;
    private LocalDate returnDate;
    private BigDecimal capitalPortion;
    private BigDecimal profitPortion;
    private BigDecimal amountTotal;
    private String notes;
    private String reference;
    private UUID recordedBy;
    private Instant createdAt;
}
