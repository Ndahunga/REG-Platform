package rw.terimbere.csams.modules.rotation.reconciliation;

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
import rw.terimbere.csams.modules.memberpayment.entity.VerificationMethod;
import rw.terimbere.csams.shared.common.entity.BaseEntity;

/**
 * Confirmation that part of a legacy contribution's paid amount was really received. It creates no ledger entry and
 * changes no balance: the money is already in the ledger once. When approved by an independent authorizer it makes the
 * amount verified and therefore payout-eligible. The period, the original scheduled recipient and the snapshot all come
 * from what the obligation already carries; nothing here is invented.
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "legacy_rotating_reconciliations")
public class LegacyRotatingReconciliation extends BaseEntity {

    @Column(name = "cooperative_id", nullable = false, updatable = false)
    private UUID cooperativeId;

    @Column(name = "contribution_id", nullable = false, updatable = false)
    private UUID contributionId;

    @Column(name = "member_user_id", nullable = false, updatable = false)
    private UUID memberUserId;

    @Column(name = "period_year", nullable = false, updatable = false)
    private int periodYear;

    @Column(name = "period_month", nullable = false, updatable = false)
    private int periodMonth;

    @Column(name = "rotation_period_id", nullable = false, updatable = false)
    private UUID rotationPeriodId;

    @Column(name = "original_recipient_user_id", nullable = false, updatable = false)
    private UUID originalRecipientUserId;

    @Column(name = "amount", nullable = false, updatable = false, precision = 19, scale = 4)
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private LegacyReconciliationStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "method", nullable = false, updatable = false, length = 32)
    private VerificationMethod method;

    @Column(name = "basis", nullable = false, updatable = false, length = 1000)
    private String basis;

    @Column(name = "reference", updatable = false, length = 128)
    private String reference;

    /** {@code method|normalizedReference} while the request is live; released when declined or withdrawn. */
    @Column(name = "reference_key", length = 170)
    private String referenceKey;

    @Column(name = "requested_by", nullable = false, updatable = false)
    private UUID requestedBy;

    @Column(name = "requested_at", nullable = false, updatable = false)
    private Instant requestedAt;

    @Column(name = "decided_by")
    private UUID decidedBy;

    @Column(name = "decided_at")
    private Instant decidedAt;

    @Column(name = "decision_note", length = 2000)
    private String decisionNote;

    @Column(name = "withdrawn_by")
    private UUID withdrawnBy;

    @Column(name = "withdrawn_at")
    private Instant withdrawnAt;

    /** The contribution id while the request is open: the database allows one open request per obligation. */
    @Column(name = "open_key")
    private UUID openKey;
}
