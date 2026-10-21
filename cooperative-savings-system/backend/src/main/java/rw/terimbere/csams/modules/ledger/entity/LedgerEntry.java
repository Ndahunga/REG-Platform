package rw.terimbere.csams.modules.ledger.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import rw.terimbere.csams.shared.financial.LedgerTransactionType;

/**
 * Immutable financial ledger entry. Do not edit money fields after insert;
 * corrections use reversal entries plus a new approved entry.
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "financial_ledger")
public class LedgerEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(nullable = false, updatable = false)
    private UUID id;

    @Column(name = "cooperative_id", nullable = false, updatable = false)
    private UUID cooperativeId;

    @Column(name = "member_user_id", updatable = false)
    private UUID memberUserId;

    @Enumerated(EnumType.STRING)
    @Column(name = "transaction_type", nullable = false, length = 64, updatable = false)
    private LedgerTransactionType transactionType;

    @Builder.Default
    @Column(name = "debit_amount", nullable = false, precision = 19, scale = 4, updatable = false)
    private BigDecimal debitAmount = BigDecimal.ZERO;

    @Builder.Default
    @Column(name = "credit_amount", nullable = false, precision = 19, scale = 4, updatable = false)
    private BigDecimal creditAmount = BigDecimal.ZERO;

    @Builder.Default
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "currency", nullable = false, length = 3, updatable = false)
    private String currency = "RWF";

    @Column(name = "transaction_date", nullable = false, updatable = false)
    private LocalDate transactionDate;

    @Column(name = "reference", length = 128, updatable = false)
    private String reference;

    @Column(name = "source_entity_type", nullable = false, length = 64, updatable = false)
    private String sourceEntityType;

    @Column(name = "source_entity_id", nullable = false, updatable = false)
    private UUID sourceEntityId;

    @Column(name = "description", length = 2000, updatable = false)
    private String description;

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private LedgerEntryStatus status = LedgerEntryStatus.APPROVED;

    @Column(name = "recorded_by", updatable = false)
    private UUID recordedBy;

    @Column(name = "approved_by", updatable = false)
    private UUID approvedBy;

    @Column(name = "reverses_entry_id", updatable = false)
    private UUID reversesEntryId;

    @Column(name = "idempotency_key", nullable = false, length = 128, updatable = false)
    private String idempotencyKey;

    /**
     * Of a contribution entry's amount: the part owed to the period's recipient (rotating) and the part that is member
     * capital (retained). Together they are exactly the entry's own amount, so they never add cash. Null on entries that
     * are not contribution cash and on entries written before the split existed.
     */
    @Column(name = "rotating_amount", precision = 19, scale = 4, updatable = false)
    private BigDecimal rotatingAmount;

    @Column(name = "retained_amount", precision = 19, scale = 4, updatable = false)
    private BigDecimal retainedAmount;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
        if (status == null) {
            status = LedgerEntryStatus.APPROVED;
        }
        if (debitAmount == null) {
            debitAmount = BigDecimal.ZERO;
        }
        if (creditAmount == null) {
            creditAmount = BigDecimal.ZERO;
        }
        if (currency == null) {
            currency = "RWF";
        }
    }
}
