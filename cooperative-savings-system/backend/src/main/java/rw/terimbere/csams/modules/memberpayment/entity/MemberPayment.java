package rw.terimbere.csams.modules.memberpayment.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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
import rw.terimbere.csams.shared.common.entity.BaseEntity;

/**
 * A claim that a member paid money to their Saving Scheme. It is not a financial transaction until it has been
 * independently verified and posted; until then no balance, ledger or report reads it.
 *
 * <p>Status changes go through {@code MemberPaymentService}; the database also enforces the consistency of
 * status with its supporting facts (see V28).
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "member_payments")
public class MemberPayment extends BaseEntity {

    @Column(name = "cooperative_id", nullable = false, updatable = false)
    private UUID cooperativeId;

    @Column(name = "member_user_id", nullable = false, updatable = false)
    private UUID memberUserId;

    @Column(name = "amount", nullable = false, updatable = false, precision = 19, scale = 4)
    private BigDecimal amount;

    /** Sum of the allocations. Must equal {@link #amount} before the payment can be verified. */
    @Builder.Default
    @Column(name = "allocated_amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal allocatedAmount = BigDecimal.ZERO;

    @Column(name = "currency", nullable = false, updatable = false, length = 3)
    private String currency;

    @Column(name = "payment_date", nullable = false, updatable = false)
    private LocalDate paymentDate;

    @Enumerated(EnumType.STRING)
    @Column(name = "payment_method", nullable = false, updatable = false, length = 32)
    private PaymentMethod paymentMethod;

    /** Exactly what the submitter entered. */
    @Column(name = "transaction_reference", updatable = false, length = 128)
    private String transactionReference;

    /** Upper-cased alphanumerics only; see {@code PaymentReferenceNormalizer}. */
    @Column(name = "normalized_reference", updatable = false, length = 128)
    private String normalizedReference;

    /**
     * {@code method|normalizedReference} while the payment holds its reference, {@code null} once released
     * (REJECTED). Unique per Saving Scheme.
     */
    @Column(name = "reference_key", length = 170)
    private String referenceKey;

    @Column(name = "evidence_file_key", updatable = false, length = 512)
    private String evidenceFileKey;

    @Column(name = "idempotency_key", updatable = false, length = 128)
    private String idempotencyKey;

    @Column(name = "notes", updatable = false, length = 2000)
    private String notes;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private PaymentStatus status;

    @Column(name = "submitted_by", nullable = false, updatable = false)
    private UUID submittedBy;

    @Column(name = "submitted_at", nullable = false, updatable = false)
    private Instant submittedAt;

    @Column(name = "review_started_by")
    private UUID reviewStartedBy;

    @Column(name = "review_started_at")
    private Instant reviewStartedAt;

    @Column(name = "verified_by")
    private UUID verifiedBy;

    @Column(name = "verified_at")
    private Instant verifiedAt;

    @Column(name = "rejected_by")
    private UUID rejectedBy;

    @Column(name = "rejected_at")
    private Instant rejectedAt;

    @Column(name = "rejection_reason", length = 2000)
    private String rejectionReason;

    @Column(name = "posted_by")
    private UUID postedBy;

    @Column(name = "posted_at")
    private Instant postedAt;

    @Column(name = "reversed_by")
    private UUID reversedBy;

    @Column(name = "reversed_at")
    private Instant reversedAt;

    @Column(name = "reversal_reason", length = 2000)
    private String reversalReason;

    // Receipt analysis (all optional, advisory only, never read by the workflow).

    @Column(name = "extracted_amount", precision = 19, scale = 4)
    private BigDecimal extractedAmount;

    @Column(name = "extracted_reference", length = 128)
    private String extractedReference;

    @Enumerated(EnumType.STRING)
    @Column(name = "receipt_status", length = 32)
    private ReceiptStatus receiptStatus;

    @Column(name = "receipt_confidence", precision = 5, scale = 4)
    private BigDecimal receiptConfidence;

    @Column(name = "receipt_analyzed_at")
    private Instant receiptAnalyzedAt;

    /** The analysis of the receipt this submission was checked against; set once, at submission. */
    @Column(name = "receipt_analysis_id", updatable = false)
    private UUID receiptAnalysisId;

    /** True when the payer chose to continue with a receipt that needed a manual review; officers see it. */
    @Builder.Default
    @Column(name = "receipt_review_requested", nullable = false, updatable = false)
    private boolean receiptReviewRequested = false;

    /** MATCH, MISMATCH or UNKNOWN: the receipt's recipient compared with the scheme's payment accounts. */
    @Column(name = "receipt_recipient_match", length = 16, updatable = false)
    private String receiptRecipientMatch;

    @Builder.Default
    @Column(name = "receipt_amount_ambiguous", nullable = false, updatable = false)
    private boolean receiptAmountAmbiguous = false;

    // Verification evidence (V33). Written once; never silently overwritten.

    @Enumerated(EnumType.STRING)
    @Column(name = "verification_method", length = 32)
    private VerificationMethod verificationMethod;

    @Column(name = "verification_basis", length = 1000)
    private String verificationBasis;

    @Column(name = "verification_reference", length = 128)
    private String verificationReference;

    @Column(name = "evidence_completed_by")
    private UUID evidenceCompletedBy;

    @Column(name = "evidence_completed_at")
    private Instant evidenceCompletedAt;

    // Posting attempt metadata (V33), written in its own transaction when an attempt fails.

    @Builder.Default
    @Column(name = "posting_attempts", nullable = false)
    private int postingAttempts = 0;

    @Column(name = "last_posting_failure_code", length = 64)
    private String lastPostingFailureCode;

    @Column(name = "last_posting_failed_at")
    private Instant lastPostingFailedAt;

    /**
     * True only for a payment that was already VERIFIED, POSTED or REVERSED when strict verification was introduced
     * (V39). It was accepted under the earlier rules; every newer payment must carry a CONFIRMED check.
     */
    @Builder.Default
    @Column(name = "legacy_verification_exception", nullable = false, updatable = false)
    private boolean legacyVerificationException = false;

    /** True once the receipt of funds has a recorded, credible confirmation (OCR output never counts). */
    public boolean hasVerificationEvidence() {
        return verificationMethod != null && verificationBasis != null;
    }
}
