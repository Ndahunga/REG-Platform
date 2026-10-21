package rw.terimbere.csams.modules.memberpayment.receipt;

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
import rw.terimbere.csams.modules.memberpayment.account.RecipientMatch;
import rw.terimbere.csams.modules.memberpayment.entity.ReceiptStatus;
import rw.terimbere.csams.shared.common.entity.BaseEntity;

/**
 * What the system read from a receipt BEFORE the payment it supports was submitted, per uploader and file content. It is
 * advisory: it decides whether a submission is allowed, held for a manual review or refused as plainly failed or pending,
 * and it is stored so a refused upload stays on record. It never confirms, verifies or posts anything.
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "receipt_analyses")
public class ReceiptAnalysis extends BaseEntity {

    @Column(name = "cooperative_id", nullable = false, updatable = false)
    private UUID cooperativeId;

    @Column(name = "uploaded_by", nullable = false, updatable = false)
    private UUID uploadedBy;

    @Column(name = "file_storage_key", nullable = false, updatable = false, length = 1024)
    private String fileStorageKey;

    @Column(name = "file_sha256", nullable = false, updatable = false, length = 64)
    private String fileSha256;

    @Column(name = "content_type", length = 64)
    private String contentType;

    @Column(name = "engine", length = 64)
    private String engine;

    /** COMPLETED, FAILED, UNAVAILABLE or UNSUPPORTED: whether the receipt could be read at all. */
    @Column(name = "reading_status", nullable = false, length = 16)
    private String readingStatus;

    @Column(name = "failure_code", length = 64)
    private String failureCode;

    @Enumerated(EnumType.STRING)
    @Column(name = "receipt_status", length = 32)
    private ReceiptStatus receiptStatus;

    @Column(name = "mean_confidence", precision = 5, scale = 4)
    private BigDecimal meanConfidence;

    @Column(name = "extracted_amount", precision = 19, scale = 4)
    private BigDecimal extractedAmount;

    @Column(name = "extracted_currency", length = 3)
    private String extractedCurrency;

    @Column(name = "extracted_reference", length = 128)
    private String extractedReference;

    @Column(name = "reference_confidence", precision = 5, scale = 4)
    private BigDecimal referenceConfidence;

    @Column(name = "extracted_paid_at")
    private Instant extractedPaidAt;

    @Column(name = "extracted_sender", length = 64)
    private String extractedSender;

    @Column(name = "extracted_recipient", length = 64)
    private String extractedRecipient;

    @Column(name = "extracted_provider", length = 32)
    private String extractedProvider;

    @Builder.Default
    @Column(name = "amount_ambiguous", nullable = false)
    private boolean amountAmbiguous = false;

    /** Comma-separated plain amounts the receipt showed as competing payment amounts; set when ambiguous. */
    @Column(name = "amount_candidates", length = 500)
    private String amountCandidates;

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(name = "recipient_match", nullable = false, length = 16)
    private RecipientMatch recipientMatch = RecipientMatch.UNKNOWN;
}
