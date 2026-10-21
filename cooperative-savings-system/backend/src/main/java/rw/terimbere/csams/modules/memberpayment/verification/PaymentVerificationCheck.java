package rw.terimbere.csams.modules.memberpayment.verification;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.Immutable;
import rw.terimbere.csams.modules.memberpayment.entity.VerificationMethod;

/** One verification outcome of a payment. Append-only: the latest row is the current outcome. */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Immutable
@Entity
@Table(name = "payment_verification_checks")
public class PaymentVerificationCheck {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(nullable = false, updatable = false)
    private UUID id;

    @Column(name = "cooperative_id", nullable = false, updatable = false)
    private UUID cooperativeId;

    @Column(name = "payment_id", nullable = false, updatable = false)
    private UUID paymentId;

    @Enumerated(EnumType.STRING)
    @Column(name = "method", nullable = false, updatable = false, length = 32)
    private VerificationMethod method;

    @Enumerated(EnumType.STRING)
    @Column(name = "outcome", nullable = false, updatable = false, length = 16)
    private VerificationOutcome outcome;

    @Enumerated(EnumType.STRING)
    @Column(name = "source", nullable = false, updatable = false, length = 32)
    private VerificationSource source;

    @Column(name = "trusted_transaction_id", updatable = false)
    private UUID trustedTransactionId;

    /** Comma-separated machine-readable reasons, e.g. AMOUNT_MISMATCH,WRONG_RECIPIENT. */
    @Column(name = "reason_codes", updatable = false, length = 500)
    private String reasonCodes;

    @Column(name = "detail", updatable = false, length = 1000)
    private String detail;

    /** The officer who made the check; null when the system recorded it. */
    @Column(name = "checked_by", updatable = false)
    private UUID checkedBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}
