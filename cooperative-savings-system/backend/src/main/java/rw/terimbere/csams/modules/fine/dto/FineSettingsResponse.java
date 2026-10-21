package rw.terimbere.csams.modules.fine.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import rw.terimbere.csams.modules.fine.entity.FineCalculationMode;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FineSettingsResponse {

    private UUID id;
    private UUID cooperativeId;
    private boolean autoFinesEnabled;
    private FineCalculationMode fineMode;
    private BigDecimal baseFineAmount;
    private BigDecimal dailyIncrement;
    private int graceDays;
    private String currency;
    private Instant createdAt;
    private Instant updatedAt;
}
