package rw.terimbere.csams.modules.rotation.payout;

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
 * One payout of a recipient's entitlement for one rotation period. The amount, the entitlement and the available funds
 * the system saw at request time are recorded and never change; only the workflow fields move forward.
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "rotation_payouts")
public class RotationPayout extends BaseEntity {

    @Column(name = "cooperative_id", nullable = false, updatable = false)
    private UUID cooperativeId;

    @Column(name = "rotation_period_id", nullable = false, updatable = false)
    private UUID rotationPeriodId;

    @Column(name = "recipient_user_id", nullable = false, updatable = false)
    private UUID recipientUserId;

    @Column(name = "amount", nullable = false, updatable = false, precision = 19, scale = 4)
    private BigDecimal amount;

    @Column(name = "entitlement_at_request", nullable = false, updatable = false, precision = 19, scale = 4)
    private BigDecimal entitlementAtRequest;

    @Column(name = "available_at_request", nullable = false, updatable = false, precision = 19, scale = 4)
    private BigDecimal availableAtRequest;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private RotationPayoutStatus status;

    @Column(name = "idempotency_key", updatable = false, length = 128)
    private String idempotencyKey;

    @Column(name = "notes", updatable = false, length = 2000)
    private String notes;

    @Column(name = "requested_by", nullable = false, updatable = false)
    private UUID requestedBy;

    @Column(name = "requested_at", nullable = false, updatable = false)
    private Instant requestedAt;

    @Column(name = "approved_by")
    private UUID approvedBy;

    @Column(name = "approved_at")
    private Instant approvedAt;

    @Column(name = "rejected_by")
    private UUID rejectedBy;

    @Column(name = "rejected_at")
    private Instant rejectedAt;

    @Column(name = "rejection_reason", length = 2000)
    private String rejectionReason;

    @Column(name = "cancelled_by")
    private UUID cancelledBy;

    @Column(name = "cancelled_at")
    private Instant cancelledAt;

    @Column(name = "cancel_reason", length = 2000)
    private String cancelReason;

    @Column(name = "disbursed_by")
    private UUID disbursedBy;

    @Column(name = "disbursed_at")
    private Instant disbursedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "disbursement_method", length = 32)
    private PaymentMethod disbursementMethod;

    @Column(name = "disbursement_reference", length = 128)
    private String disbursementReference;

    @Column(name = "disbursement_note", length = 2000)
    private String disbursementNote;

    @Column(name = "ledger_entry_id")
    private UUID ledgerEntryId;

    /** The period id while the payout is open (REQUESTED or APPROVED), otherwise null. */
    @Column(name = "open_key")
    private UUID openKey;
}
