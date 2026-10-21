package rw.terimbere.csams.modules.memberpayment.verification;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import rw.terimbere.csams.modules.memberpayment.entity.PaymentMethod;
import rw.terimbere.csams.shared.common.entity.BaseEntity;

/**
 * Money that really moved, as recorded by an authoritative source: a statement line imported by an identified officer
 * or a signed provider callback. A reference typed by a payer, or read from an image, is not a trusted transaction.
 *
 * <p>Unique per scheme, channel and reference (a repeated import or callback is the same transaction), and consumable
 * by one payment only. A later record that disagrees marks it conflicted; it is never overwritten.
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "trusted_transactions")
public class TrustedTransaction extends BaseEntity {

    @Column(name = "cooperative_id", nullable = false, updatable = false)
    private UUID cooperativeId;

    @Enumerated(EnumType.STRING)
    @Column(name = "source", nullable = false, updatable = false, length = 32)
    private VerificationSource source;

    @Enumerated(EnumType.STRING)
    @Column(name = "channel", nullable = false, updatable = false, length = 32)
    private PaymentMethod channel;

    @Column(name = "transaction_reference", nullable = false, updatable = false, length = 128)
    private String transactionReference;

    @Column(name = "normalized_reference", nullable = false, updatable = false, length = 128)
    private String normalizedReference;

    @Column(name = "amount", nullable = false, updatable = false, precision = 19, scale = 4)
    private BigDecimal amount;

    @Column(name = "currency", nullable = false, updatable = false, length = 3)
    private String currency;

    @Column(name = "transacted_at", updatable = false)
    private Instant transactedAt;

    @Column(name = "sender", updatable = false, length = 64)
    private String sender;

    @Column(name = "recipient", updatable = false, length = 64)
    private String recipient;

    @Enumerated(EnumType.STRING)
    @Column(name = "provider_status", nullable = false, length = 16)
    private ProviderStatus providerStatus;

    @Column(name = "event_id", updatable = false, length = 128)
    private String eventId;

    @Column(name = "batch_id", updatable = false)
    private UUID batchId;

    /** The officer who imported it; null for a provider callback. */
    @Column(name = "recorded_by", updatable = false)
    private UUID recordedBy;

    @Column(name = "recorded_at", nullable = false, updatable = false)
    private Instant recordedAt;

    @Column(name = "consumed_by_payment_id")
    private UUID consumedByPaymentId;

    @Column(name = "consumed_at")
    private Instant consumedAt;

    @Column(name = "conflicted_at")
    private Instant conflictedAt;

    @Column(name = "conflict_detail", length = 500)
    private String conflictDetail;

    public boolean isConflicted() {
        return conflictedAt != null;
    }
}
