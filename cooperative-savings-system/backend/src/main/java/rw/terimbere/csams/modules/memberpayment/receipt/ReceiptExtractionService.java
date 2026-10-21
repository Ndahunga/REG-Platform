package rw.terimbere.csams.modules.memberpayment.receipt;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;
import rw.terimbere.csams.modules.audit.service.AuditService;
import rw.terimbere.csams.modules.filemanagement.entity.StoredFile;
import rw.terimbere.csams.modules.filemanagement.service.FileManagementService;
import rw.terimbere.csams.modules.memberpayment.entity.MemberPayment;
import rw.terimbere.csams.modules.memberpayment.entity.PaymentMethod;
import rw.terimbere.csams.modules.memberpayment.receipt.ReceiptReader.Outcome;
import rw.terimbere.csams.modules.memberpayment.receipt.ReceiptTextParser.Field;
import rw.terimbere.csams.modules.memberpayment.receipt.ReceiptTextParser.Parsed;
import rw.terimbere.csams.modules.memberpayment.repository.MemberPaymentRepository;
import rw.terimbere.csams.modules.memberpayment.service.PaymentReferenceNormalizer;
import rw.terimbere.csams.security.CooperativeAuthorizationService;
import rw.terimbere.csams.security.UserPrincipal;
import rw.terimbere.csams.shared.auditing.AuditableAction;
import rw.terimbere.csams.shared.exceptions.BusinessException;
import rw.terimbere.csams.shared.exceptions.ForbiddenException;
import rw.terimbere.csams.shared.exceptions.ResourceNotFoundException;

/**
 * Secure processing of the receipt uploaded as evidence of a payment.
 *
 * <p>The original receipt is never touched. Its content is verified (size cap, and the first bytes must be a PNG or a
 * JPEG: a declared type that disagrees with the real content is refused as a forged file, and PDFs are recorded as
 * unsupported rather than handed to an engine), hashed, and only then read. Processing the same content for the same
 * payment again returns the stored extraction, so there is one extraction per receipt, and the same receipt used on a
 * different payment of the scheme is flagged as a duplicate.
 *
 * <p>The result is advisory. It is compared with what the payer claimed and every difference is reported, but it can
 * only ever add doubt: it never verifies, confirms or posts anything.
 */
@Service
@RequiredArgsConstructor
public class ReceiptExtractionService {

    private static final Logger log = LoggerFactory.getLogger(ReceiptExtractionService.class);
    private static final String CONTRIBUTION_WRITE = "CONTRIBUTION_WRITE";
    private static final String LOAN_WRITE = "LOAN_WRITE";
    private static final int MAX_RAW_TEXT = 8000;
    private static final double LOW_CONFIDENCE = 0.5;

    public static final String COMPLETED = ReceiptReader.COMPLETED;
    public static final String FAILED = ReceiptReader.FAILED;
    public static final String UNAVAILABLE = ReceiptReader.UNAVAILABLE;
    public static final String UNSUPPORTED = ReceiptReader.UNSUPPORTED;

    private final MemberPaymentRepository paymentRepository;
    private final PaymentReceiptExtractionRepository extractionRepository;
    private final FileManagementService fileManagementService;
    private final List<ReceiptOcrEngine> engines;
    private final ReceiptReader reader;
    private final CooperativeAuthorizationService authorizationService;
    private final AuditService auditService;
    private final TransactionTemplate transactionTemplate;
    private final ObjectMapper objectMapper;
    private final rw.terimbere.csams.modules.memberpayment.verification.PaymentVerificationCheckService
            verificationCheckService;

    @Value("${app.ocr.max-bytes:8388608}")
    private long maxBytes;

    @Value("${app.ocr.timeout-seconds:30}")
    private long timeoutSeconds;

    /** What this deployment can read, so the screens can say so before anyone tries. */
    public Capabilities capabilities(UUID cooperativeId) {
        requireStaff(cooperativeId);
        ReceiptOcrEngine engine = engines.stream().filter(ReceiptOcrEngine::available).findFirst().orElse(null);
        return new Capabilities(
                engine != null,
                engine == null ? null : engine.name(),
                List.of("image/png", "image/jpeg", "application/pdf"),
                true,
                maxBytes,
                timeoutSeconds);
    }

    /** @param pdfSupported PDF receipts are read: their text layer directly, or an embedded picture through OCR */
    public record Capabilities(
            boolean ocrAvailable,
            String ocrEngine,
            List<String> supportedReceiptTypes,
            boolean pdfSupported,
            long maxReceiptBytes,
            long ocrTimeoutSeconds) {}

    // ------------------------------------------------------------------ extract

    /**
     * Reads the payment's receipt. Not transactional on purpose: the OCR run can take seconds and must not hold a
     * database transaction (or a payment row lock) open.
     */
    public ExtractionResponse extract(UUID cooperativeId, UUID paymentId) {
        UserPrincipal actor = requireStaff(cooperativeId);
        MemberPayment payment = paymentRepository
                .findByIdAndCooperativeId(paymentId, cooperativeId)
                .orElseThrow(() -> new ResourceNotFoundException("Payment", paymentId));
        if (!StringUtils.hasText(payment.getEvidenceFileKey())) {
            throw new BusinessException("RECEIPT_NOT_UPLOADED", "This payment has no uploaded receipt");
        }
        StoredFile stored = fileManagementService.requireOwnedFile(payment.getEvidenceFileKey(), cooperativeId);
        byte[] content = readBounded(payment.getEvidenceFileKey());
        String sha256 = sha256(content);

        PaymentReceiptExtraction existing =
                extractionRepository.findByPaymentIdAndFileSha256(paymentId, sha256).orElse(null);
        if (existing != null && (COMPLETED.equals(existing.getStatus()) || UNSUPPORTED.equals(existing.getStatus()))) {
            return toResponse(existing, payment);
        }

        Outcome outcome = process(content, stored.getContentType());
        PaymentReceiptExtraction saved = persist(payment, stored, sha256, outcome, actor, existing);
        auditService.record(
                actor.getId(),
                cooperativeId,
                AuditableAction.RECEIPT_EXTRACTED,
                "MemberPayment",
                paymentId,
                null,
                "{\"status\":\"" + saved.getStatus() + "\",\"attempts\":" + saved.getAttempts() + "}",
                null,
                null);
        // what the receipt says is held against the trusted records (it can add a conflict, never a confirmation)
        verificationCheckService.evaluateSystem(cooperativeId, paymentId);
        return toResponse(saved, payment);
    }

    /** Everything the staff may look at for one payment's receipt. */
    public List<ExtractionResponse> forPayment(UUID cooperativeId, UUID paymentId) {
        requireStaff(cooperativeId);
        MemberPayment payment = paymentRepository
                .findByIdAndCooperativeId(paymentId, cooperativeId)
                .orElseThrow(() -> new ResourceNotFoundException("Payment", paymentId));
        List<ExtractionResponse> result = new ArrayList<>();
        for (PaymentReceiptExtraction e : extractionRepository.findByCooperativeIdAndPaymentIdOrderByCreatedAtAsc(cooperativeId, paymentId)) {
            result.add(toResponse(e, payment));
        }
        return result;
    }

    /** An authorized officer records that they looked at the extraction. Recorded once and never overwritten. */
    public ExtractionResponse review(UUID cooperativeId, UUID extractionId, String note) {
        UserPrincipal actor = requireStaff(cooperativeId);
        PaymentReceiptExtraction extraction = extractionRepository
                .findByIdAndCooperativeId(extractionId, cooperativeId)
                .orElseThrow(() -> new ResourceNotFoundException("Receipt extraction", extractionId));
        MemberPayment payment = paymentRepository
                .findByIdAndCooperativeId(extraction.getPaymentId(), cooperativeId)
                .orElseThrow(() -> new ResourceNotFoundException("Payment", extraction.getPaymentId()));
        if (actor.getId().equals(payment.getMemberUserId())) {
            throw new ForbiddenException("You cannot review the receipt of your own payment");
        }
        if (extraction.getReviewedBy() != null) {
            return toResponse(extraction, payment);
        }
        PaymentReceiptExtraction saved = transactionTemplate.execute(status -> {
            PaymentReceiptExtraction fresh = extractionRepository.findById(extractionId).orElseThrow();
            if (fresh.getReviewedBy() == null) {
                fresh.setReviewedBy(actor.getId());
                fresh.setReviewedAt(Instant.now());
                fresh.setReviewNote(StringUtils.hasText(note) ? note.trim() : null);
                fresh = extractionRepository.saveAndFlush(fresh);
                auditService.record(
                        actor.getId(),
                        cooperativeId,
                        AuditableAction.RECEIPT_REVIEWED,
                        "PaymentReceiptExtraction",
                        extractionId,
                        null,
                        "{\"paymentId\":\"" + payment.getId() + "\"}",
                        null,
                        null);
            }
            return fresh;
        });
        return toResponse(saved, payment);
    }

    // ------------------------------------------------------------------ processing

    private Outcome process(byte[] content, String declaredType) {
        return reader.read(content, declaredType);
    }

    private PaymentReceiptExtraction persist(
            MemberPayment payment,
            StoredFile stored,
            String sha256,
            Outcome outcome,
            UserPrincipal actor,
            PaymentReceiptExtraction existing) {
        try {
            return transactionTemplate.execute(status -> {
                PaymentReceiptExtraction row = existing == null
                        ? PaymentReceiptExtraction.builder()
                                .cooperativeId(payment.getCooperativeId())
                                .paymentId(payment.getId())
                                .fileStorageKey(stored.getStorageKey())
                                .fileSha256(sha256)
                                .createdBy(actor.getId())
                                .build()
                        : extractionRepository.findById(existing.getId()).orElseThrow();
                if (existing != null) {
                    row.setAttempts(row.getAttempts() + 1);
                }
                apply(row, outcome);
                row.setDuplicateOfPaymentId(extractionRepository
                        .findFirstByCooperativeIdAndFileSha256AndPaymentIdNotOrderByCreatedAtAsc(
                                payment.getCooperativeId(), sha256, payment.getId())
                        .map(PaymentReceiptExtraction::getPaymentId)
                        .orElse(null));
                return extractionRepository.saveAndFlush(row);
            });
        } catch (DataIntegrityViolationException race) {
            // a concurrent request stored the same receipt first: there is exactly one extraction per receipt
            return extractionRepository.findByPaymentIdAndFileSha256(payment.getId(), sha256).orElseThrow();
        }
    }

    private void apply(PaymentReceiptExtraction row, Outcome outcome) {
        row.setStatus(outcome.status());
        row.setFailureCode(outcome.failureCode());
        row.setEngine(outcome.engine());
        row.setContentType(outcome.contentType());
        if (outcome.ocr() == null) {
            return;
        }
        String text = outcome.ocr().text();
        row.setRawText(text.length() > MAX_RAW_TEXT ? text.substring(0, MAX_RAW_TEXT) : text);
        row.setMeanConfidence(BigDecimal.valueOf(outcome.ocr().meanConfidence()).setScale(4, java.math.RoundingMode.HALF_UP));
        Parsed p = outcome.parsed();
        row.setAmountAmbiguous(p.amountAmbiguous());
        Map<String, Double> confidences = new LinkedHashMap<>();
        if (p.reference() != null) {
            row.setExtractedReference(p.reference().value());
            confidences.put("reference", p.reference().confidence());
        }
        if (p.amount() != null) {
            row.setExtractedAmount(p.amount().value().setScale(4, java.math.RoundingMode.HALF_UP));
            confidences.put("amount", p.amount().confidence());
        }
        if (p.currency() != null) {
            row.setExtractedCurrency(p.currency().value());
            confidences.put("currency", p.currency().confidence());
        }
        if (p.paidAt() != null) {
            row.setExtractedPaidAt(p.paidAt().value().atZone(ReceiptTextParser.KIGALI).toInstant());
            confidences.put("paidAt", p.paidAt().confidence());
        }
        if (p.sender() != null) {
            row.setExtractedSender(p.sender().value());
            confidences.put("sender", p.sender().confidence());
        }
        if (p.recipient() != null) {
            row.setExtractedRecipient(p.recipient().value());
            confidences.put("recipient", p.recipient().confidence());
        }
        if (p.provider() != null) {
            row.setExtractedProvider(p.provider().value().name());
            confidences.put("provider", p.provider().confidence());
        }
        if (p.status() != null) {
            row.setExtractedStatus(p.status().value().name());
            confidences.put("status", p.status().confidence());
        }
        try {
            row.setFieldConfidences(objectMapper.writeValueAsString(confidences));
        } catch (JsonProcessingException e) {
            row.setFieldConfidences(null);
        }
    }

    // ------------------------------------------------------------------ content checks

    /** "png" or "jpg" from the real first bytes, or null for anything else. */
    static String detectType(byte[] content) {
        return ReceiptReader.detectType(content);
    }

    private byte[] readBounded(String storageKey) {
        Resource resource = fileManagementService.loadAsResource(storageKey);
        try (InputStream in = resource.getInputStream()) {
            byte[] data = in.readNBytes((int) maxBytes + 1);
            if (data.length > maxBytes) {
                throw new BusinessException("RECEIPT_TOO_LARGE", "The receipt is larger than the allowed size");
            }
            return data;
        } catch (IOException e) {
            throw new BusinessException("RECEIPT_UNREADABLE", "The receipt could not be read");
        }
    }

    private static String sha256(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    // ------------------------------------------------------------------ response

    private ExtractionResponse toResponse(PaymentReceiptExtraction e, MemberPayment payment) {
        Map<String, Double> confidences = new LinkedHashMap<>();
        if (StringUtils.hasText(e.getFieldConfidences())) {
            try {
                confidences = objectMapper.readValue(e.getFieldConfidences(), new TypeReference<LinkedHashMap<String, Double>>() {});
            } catch (JsonProcessingException ignored) {
                // an unreadable confidence map shows as "unknown", never as high confidence
            }
        }
        List<FieldValue> fields = new ArrayList<>();
        addField(fields, "reference", e.getExtractedReference(), confidences);
        addField(fields, "amount", e.getExtractedAmount() == null ? null : e.getExtractedAmount().toPlainString(), confidences);
        addField(fields, "currency", e.getExtractedCurrency(), confidences);
        addField(fields, "paidAt", e.getExtractedPaidAt() == null ? null : e.getExtractedPaidAt().toString(), confidences);
        addField(fields, "sender", e.getExtractedSender(), confidences);
        addField(fields, "recipient", e.getExtractedRecipient(), confidences);
        addField(fields, "provider", e.getExtractedProvider(), confidences);
        addField(fields, "status", e.getExtractedStatus(), confidences);
        return new ExtractionResponse(
                e.getId(),
                e.getPaymentId(),
                e.getStatus(),
                e.getFailureCode(),
                e.getEngine(),
                e.getAttempts(),
                e.getMeanConfidence(),
                fields,
                e.getRawText(),
                e.getDuplicateOfPaymentId() != null,
                e.getDuplicateOfPaymentId(),
                discrepancies(e, payment),
                false,
                e.getReviewedBy(),
                e.getReviewedAt(),
                e.getReviewNote(),
                e.getCreatedAt());
    }

    private static void addField(List<FieldValue> fields, String name, String value, Map<String, Double> confidences) {
        if (value == null) {
            return;
        }
        Double c = confidences.get(name);
        BigDecimal confidence = c == null ? null : BigDecimal.valueOf(c).setScale(4, java.math.RoundingMode.HALF_UP);
        String level = c == null ? "UNKNOWN" : c >= 0.8 ? "HIGH" : c >= LOW_CONFIDENCE ? "MEDIUM" : "LOW";
        fields.add(new FieldValue(name, value, confidence, level));
    }

    /** Differences between what the receipt says and what the payer claimed. They only ever add doubt. */
    static List<String> discrepancies(PaymentReceiptExtraction e, MemberPayment payment) {
        List<String> found = new ArrayList<>();
        if (!COMPLETED.equals(e.getStatus())) {
            return found;
        }
        if (e.isAmountAmbiguous()) {
            found.add("AMOUNT_AMBIGUOUS");
        }
        if (e.getExtractedAmount() != null && e.getExtractedAmount().compareTo(payment.getAmount()) != 0) {
            found.add("AMOUNT_MISMATCH");
        }
        if (e.getExtractedCurrency() != null && !e.getExtractedCurrency().equalsIgnoreCase(payment.getCurrency())) {
            found.add("CURRENCY_MISMATCH");
        }
        if (e.getExtractedReference() != null
                && payment.getNormalizedReference() != null
                && !PaymentReferenceNormalizer.normalize(e.getExtractedReference()).equals(payment.getNormalizedReference())) {
            found.add("REFERENCE_MISMATCH");
        }
        if (e.getExtractedProvider() != null
                && payment.getPaymentMethod() != PaymentMethod.OTHER
                && !e.getExtractedProvider().equals(payment.getPaymentMethod().name())) {
            found.add("PROVIDER_MISMATCH");
        }
        if (e.getExtractedPaidAt() != null) {
            LocalDate receiptDate = e.getExtractedPaidAt().atZone(ReceiptTextParser.KIGALI).toLocalDate();
            if (Math.abs(ChronoUnit.DAYS.between(receiptDate, payment.getPaymentDate())) > 1) {
                found.add("DATE_MISMATCH");
            }
        }
        if ("FAILED_INDICATED".equals(e.getExtractedStatus()) || "PENDING_INDICATED".equals(e.getExtractedStatus())) {
            found.add("RECEIPT_NOT_SUCCESSFUL");
        }
        if (e.getDuplicateOfPaymentId() != null) {
            found.add("DUPLICATE_RECEIPT");
        }
        if (e.getMeanConfidence() != null && e.getMeanConfidence().doubleValue() < LOW_CONFIDENCE) {
            found.add("LOW_CONFIDENCE");
        }
        return found;
    }

    private UserPrincipal requireStaff(UUID cooperativeId) {
        authorizationService.requireMembership(cooperativeId);
        UserPrincipal actor = authorizationService.currentPrincipal();
        if (!actor.hasRole(CooperativeAuthorizationService.SUPER_ADMIN)
                && !actor.hasAuthority(CONTRIBUTION_WRITE)
                && !actor.hasAuthority(LOAN_WRITE)) {
            throw new ForbiddenException("You do not have permission to review payment receipts");
        }
        return actor;
    }

    // ------------------------------------------------------------------ DTOs

    /** One extracted value with how sure the reading is (HIGH, MEDIUM, LOW). */
    public record FieldValue(String name, String value, BigDecimal confidence, String level) {}

    /**
     * @param proofOfPayment always false: an extraction is a claim made by an image, never evidence of receipt
     */
    public record ExtractionResponse(
            UUID id,
            UUID paymentId,
            String status,
            String failureCode,
            String engine,
            int attempts,
            BigDecimal meanConfidence,
            List<FieldValue> fields,
            String rawText,
            boolean duplicateReceipt,
            UUID duplicateOfPaymentId,
            List<String> discrepancies,
            boolean proofOfPayment,
            UUID reviewedBy,
            Instant reviewedAt,
            String reviewNote,
            Instant createdAt) {}
}
