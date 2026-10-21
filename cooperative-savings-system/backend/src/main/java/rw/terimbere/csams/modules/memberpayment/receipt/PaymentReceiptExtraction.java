package rw.terimbere.csams.modules.memberpayment.receipt;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import rw.terimbere.csams.shared.common.entity.BaseEntity;

/**
 * What OCR read from the receipt of a payment. Advisory evidence for an officer to look at: nothing in the verification,
 * posting or reconciliation workflow reads it as proof that money was received.
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "payment_receipt_extractions")
public class PaymentReceiptExtraction extends BaseEntity {

    @Column(name = "cooperative_id", nullable = false, updatable = false)
    private UUID cooperativeId;

    @Column(name = "payment_id", nullable = false, updatable = false)
    private UUID paymentId;

    @Column(name = "file_storage_key", nullable = false, updatable = false, length = 1024)
    private String fileStorageKey;

    @Column(name = "file_sha256", nullable = false, updatable = false, length = 64)
    private String fileSha256;

    @Column(name = "content_type", length = 64)
    private String contentType;

    @Column(name = "engine", length = 64)
    private String engine;

    @Column(name = "status", nullable = false, length = 16)
    private String status;

    @Column(name = "failure_code", length = 64)
    private String failureCode;

    @Builder.Default
    @Column(name = "attempts", nullable = false)
    private int attempts = 1;

    @Column(name = "raw_text", length = 8000)
    private String rawText;

    @Column(name = "mean_confidence", precision = 5, scale = 4)
    private BigDecimal meanConfidence;

    @Column(name = "extracted_reference", length = 128)
    private String extractedReference;

    @Column(name = "extracted_amount", precision = 19, scale = 4)
    private BigDecimal extractedAmount;

    /** True when the receipt showed competing payment amounts and none was chosen. */
    @Builder.Default
    @Column(name = "amount_ambiguous", nullable = false)
    private boolean amountAmbiguous = false;

    @Column(name = "extracted_currency", length = 3)
    private String extractedCurrency;

    @Column(name = "extracted_paid_at")
    private Instant extractedPaidAt;

    @Column(name = "extracted_sender", length = 64)
    private String extractedSender;

    @Column(name = "extracted_recipient", length = 64)
    private String extractedRecipient;

    @Column(name = "extracted_provider", length = 32)
    private String extractedProvider;

    @Column(name = "extracted_status", length = 32)
    private String extractedStatus;

    /** JSON object: field name to confidence from 0 to 1. */
    @Column(name = "field_confidences", length = 2000)
    private String fieldConfidences;

    @Column(name = "duplicate_of_payment_id")
    private UUID duplicateOfPaymentId;

    @Column(name = "created_by", nullable = false, updatable = false)
    private UUID createdBy;

    @Column(name = "reviewed_by")
    private UUID reviewedBy;

    @Column(name = "reviewed_at")
    private Instant reviewedAt;

    @Column(name = "review_note", length = 2000)
    private String reviewNote;
}
