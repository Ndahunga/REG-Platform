package rw.terimbere.csams.modules.ledger.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import rw.terimbere.csams.modules.ledger.entity.LedgerEntryStatus;
import rw.terimbere.csams.shared.financial.LedgerTransactionType;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LedgerEntryResponse {

    private UUID id;
    private UUID cooperativeId;
    private UUID memberUserId;
    private LedgerTransactionType transactionType;
    private BigDecimal debitAmount;
    private BigDecimal creditAmount;
    private String currency;
    private LocalDate transactionDate;
    private String reference;
    private String sourceEntityType;
    private UUID sourceEntityId;
    private String description;
    private LedgerEntryStatus status;
    private UUID recordedBy;
    private UUID approvedBy;
    private UUID reversesEntryId;
    private String idempotencyKey;
    private Instant createdAt;
}
