package rw.terimbere.csams.modules.memberpayment.receipt;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;
import rw.terimbere.csams.modules.cooperative.repository.CooperativeRepository;
import rw.terimbere.csams.modules.filemanagement.entity.StoredFile;
import rw.terimbere.csams.modules.filemanagement.service.FileManagementService;
import rw.terimbere.csams.modules.memberpayment.account.PaymentAccountService;
import rw.terimbere.csams.modules.memberpayment.account.RecipientMatch;
import rw.terimbere.csams.modules.memberpayment.entity.PaymentMethod;
import rw.terimbere.csams.modules.memberpayment.entity.ReceiptStatus;
import rw.terimbere.csams.modules.memberpayment.receipt.ReceiptAnalysisResponse.RecommendedAction;
import rw.terimbere.csams.modules.memberpayment.receipt.ReceiptReader.Outcome;
import rw.terimbere.csams.modules.memberpayment.receipt.ReceiptSubmissionPolicy.Action;
import rw.terimbere.csams.modules.memberpayment.receipt.ReceiptSubmissionPolicy.Claim;
import rw.terimbere.csams.modules.memberpayment.receipt.ReceiptSubmissionPolicy.Decision;
import rw.terimbere.csams.modules.memberpayment.receipt.ReceiptSubmissionPolicy.Facts;
import rw.terimbere.csams.modules.memberpayment.receipt.ReceiptTextParser.Parsed;
import rw.terimbere.csams.modules.memberpayment.repository.MemberPaymentRepository;
import rw.terimbere.csams.modules.memberpayment.service.PaymentReferenceNormalizer;
import rw.terimbere.csams.security.CooperativeAuthorizationService;
import rw.terimbere.csams.security.UserPrincipal;
import rw.terimbere.csams.modules.audit.service.AuditService;
import rw.terimbere.csams.shared.auditing.AuditableAction;
import rw.terimbere.csams.shared.exceptions.BusinessException;
import rw.terimbere.csams.shared.exceptions.ForbiddenException;

/**
 * The member's own look at a receipt, before the payment is submitted, and the rule that follows from it.
 *
 * <p>{@link #analyze} reads the uploaded receipt and tells the member what it appears to say and what to do next.
 * {@link #gate} is called by the payment submission itself, so the rule holds whether or not the client asked for an
 * analysis first: a receipt that says the transaction FAILED or is still PENDING refuses the submission, and a receipt that
 * cannot be trusted to be clear lets the member continue only by asking for a manual review. The analysis is stored per
 * uploader and file content, so a refused upload stays on record and the same file is never read twice.
 *
 * <p>Nothing here verifies, confirms or posts a payment. Reading is done outside any database transaction, because
 * recognising an image can take seconds.
 */
@Service
@RequiredArgsConstructor
public class ReceiptAnalysisService {

    private final ReceiptAnalysisRepository repository;
    private final FileManagementService fileManagementService;
    private final ReceiptFileLoader fileLoader;
    private final ReceiptReader reader;
    private final PaymentAccountService accountService;
    private final MemberPaymentRepository paymentRepository;
    private final CooperativeRepository cooperativeRepository;
    private final CooperativeAuthorizationService authorizationService;
    private final AuditService auditService;
    private final TransactionTemplate transactionTemplate;

    /** What a payment submission carries forward from the receipt it was checked against. */
    public record ReceiptGate(
            UUID analysisId,
            ReceiptStatus receiptStatus,
            BigDecimal confidence,
            BigDecimal extractedAmount,
            String extractedReference,
            boolean amountAmbiguous,
            RecipientMatch recipientMatch,
            boolean reviewRequested,
            Instant analyzedAt) {

        public static ReceiptGate none() {
            return new ReceiptGate(null, null, null, null, null, false, null, false, null);
        }
    }

    // ------------------------------------------------------------------ the member's analysis

    public ReceiptAnalysisResponse analyze(UUID cooperativeId, String fileKey, BigDecimal enteredAmount) {
        authorizationService.requireMembership(cooperativeId);
        UserPrincipal principal = authorizationService.currentPrincipal();
        if (!StringUtils.hasText(fileKey)) {
            throw new BusinessException("RECEIPT_FILE_REQUIRED", "Upload the proof of payment first");
        }
        ReceiptAnalysis analysis = findOrCreate(cooperativeId, principal, fileKey.trim());
        Decision decision = ReceiptSubmissionPolicy.decide(facts(analysis), claim(cooperativeId, enteredAmount, null));
        return toResponse(analysis, decision);
    }

    // ------------------------------------------------------------------ the rule at submission

    /**
     * Applies the receipt rule to a submission. Throws a stable business code when the receipt stops it
     * (RECEIPT_INDICATES_FAILURE, RECEIPT_INDICATES_PENDING) or when it needs a manual review the payer did not ask for
     * (RECEIPT_NEEDS_REVIEW). Returns what to record on the payment.
     */
    public ReceiptGate gate(
            UUID cooperativeId,
            UserPrincipal principal,
            String fileKey,
            BigDecimal amount,
            String transactionReference,
            boolean manualReviewRequested) {
        if (!StringUtils.hasText(fileKey)) {
            return ReceiptGate.none();
        }
        ReceiptAnalysis analysis = findOrCreate(cooperativeId, principal, fileKey.trim());
        String normalized = StringUtils.hasText(transactionReference)
                ? PaymentReferenceNormalizer.normalize(transactionReference)
                : null;
        Decision decision = ReceiptSubmissionPolicy.decide(facts(analysis), claim(cooperativeId, amount, normalized));
        switch (decision.action()) {
            case BLOCK_FAILED:
                throw new BusinessException(
                        "RECEIPT_INDICATES_FAILURE",
                        "The uploaded proof of payment indicates that the transaction failed. Make a successful payment"
                                + " and upload a new confirmation.");
            case BLOCK_PENDING:
                throw new BusinessException(
                        "RECEIPT_INDICATES_PENDING",
                        "The uploaded proof of payment indicates that the transaction is still processing. Upload the"
                                + " final confirmation once it is completed.");
            case REVIEW_REQUIRED:
                if (!manualReviewRequested) {
                    throw new BusinessException(
                            "RECEIPT_NEEDS_REVIEW",
                            "The receipt could not be confirmed as clear (" + String.join(", ", decision.reasons())
                                    + "). Upload a clearer receipt or submit the payment for a manual review.");
                }
                break;
            default:
                break;
        }
        return new ReceiptGate(
                analysis.getId(),
                analysis.getReceiptStatus(),
                analysis.getMeanConfidence(),
                analysis.getExtractedAmount(),
                analysis.getExtractedReference(),
                analysis.isAmountAmbiguous(),
                analysis.getRecipientMatch(),
                decision.action() == Action.REVIEW_REQUIRED,
                analysis.getCreatedAt());
    }

    // ------------------------------------------------------------------ reading and storing

    private ReceiptAnalysis findOrCreate(UUID cooperativeId, UserPrincipal principal, String fileKey) {
        StoredFile file = fileManagementService.requireOwnedFile(fileKey, cooperativeId);
        if (file.getUploadedBy() == null || !file.getUploadedBy().equals(principal.getId())) {
            throw new ForbiddenException("The receipt was not uploaded by you");
        }
        byte[] content = fileLoader.load(file.getStorageKey());
        String sha256 = ReceiptFileLoader.sha256(content);
        ReceiptAnalysis existing = repository
                .findByCooperativeIdAndUploadedByAndFileSha256(cooperativeId, principal.getId(), sha256)
                .orElse(null);
        if (existing != null) {
            return existing;
        }
        Outcome outcome = reader.read(content, file.getContentType());
        RecipientMatch match = outcome.parsed() == null || outcome.parsed().recipient() == null
                ? RecipientMatch.UNKNOWN
                : accountService.match(cooperativeId, outcome.parsed().recipient().value());
        ReceiptAnalysis built = build(cooperativeId, principal.getId(), file, sha256, outcome, match);
        ReceiptAnalysis saved;
        try {
            saved = transactionTemplate.execute(status -> repository.saveAndFlush(built));
        } catch (DataIntegrityViolationException race) {
            // a concurrent request stored the same receipt first: there is exactly one analysis per receipt
            return repository
                    .findByCooperativeIdAndUploadedByAndFileSha256(cooperativeId, principal.getId(), sha256)
                    .orElseThrow(() -> race);
        }
        auditService.record(
                principal.getId(), cooperativeId, AuditableAction.RECEIPT_ANALYZED, "ReceiptAnalysis", saved.getId(),
                null,
                "{\"reading\":\"" + saved.getReadingStatus() + "\",\"receiptStatus\":\""
                        + (saved.getReceiptStatus() == null ? "" : saved.getReceiptStatus().name()) + "\"}",
                null, null);
        return saved;
    }

    private ReceiptAnalysis build(
            UUID cooperativeId, UUID uploader, StoredFile file, String sha256, Outcome outcome, RecipientMatch match) {
        ReceiptAnalysis.ReceiptAnalysisBuilder b = ReceiptAnalysis.builder()
                .cooperativeId(cooperativeId)
                .uploadedBy(uploader)
                .fileStorageKey(file.getStorageKey())
                .fileSha256(sha256)
                .contentType(outcome.contentType())
                .engine(outcome.engine())
                .readingStatus(outcome.status())
                .failureCode(outcome.failureCode())
                .recipientMatch(match);
        if (outcome.ocr() != null) {
            b.meanConfidence(BigDecimal.valueOf(outcome.ocr().meanConfidence()).setScale(4, RoundingMode.HALF_UP));
        }
        Parsed p = outcome.parsed();
        if (p != null) {
            b.receiptStatus(p.status() == null ? ReceiptStatus.UNKNOWN : p.status().value());
            if (p.amount() != null) {
                b.extractedAmount(p.amount().value().setScale(4, RoundingMode.HALF_UP));
            }
            if (p.currency() != null) {
                b.extractedCurrency(p.currency().value());
            }
            if (p.reference() != null) {
                b.extractedReference(p.reference().value());
                b.referenceConfidence(BigDecimal.valueOf(p.reference().confidence()).setScale(4, RoundingMode.HALF_UP));
            }
            if (p.paidAt() != null) {
                b.extractedPaidAt(p.paidAt().value().atZone(ReceiptTextParser.KIGALI).toInstant());
            }
            if (p.sender() != null) {
                b.extractedSender(p.sender().value());
            }
            if (p.recipient() != null) {
                b.extractedRecipient(p.recipient().value());
            }
            if (p.provider() != null) {
                b.extractedProvider(p.provider().value().name());
            }
            b.amountAmbiguous(p.amountAmbiguous());
            if (p.amountAmbiguous() && !p.amountCandidates().isEmpty()) {
                b.amountCandidates(String.join(",", p.amountCandidates().stream().map(BigDecimal::toPlainString).toList()));
            }
        }
        return b.build();
    }

    // ------------------------------------------------------------------ views

    private Facts facts(ReceiptAnalysis a) {
        return new Facts(
                a.getReadingStatus(),
                a.getReceiptStatus(),
                a.getMeanConfidence(),
                a.getExtractedAmount(),
                a.getExtractedCurrency(),
                a.getExtractedReference(),
                a.getReferenceConfidence(),
                a.isAmountAmbiguous(),
                a.getRecipientMatch());
    }

    private Claim claim(UUID cooperativeId, BigDecimal amount, String normalizedReference) {
        String currency = cooperativeRepository
                .findByIdAndDeletedFalse(cooperativeId)
                .map(c -> c.getCurrency())
                .orElse(null);
        return new Claim(amount, normalizedReference, currency);
    }

    private ReceiptAnalysisResponse toResponse(ReceiptAnalysis a, Decision decision) {
        RecommendedAction advice = switch (decision.action()) {
            case PROCEED -> RecommendedAction.CONTINUE;
            case BLOCK_FAILED -> RecommendedAction.REPLACE_RECEIPT;
            case BLOCK_PENDING -> RecommendedAction.WAIT_FOR_FINAL_RECEIPT;
            case REVIEW_REQUIRED -> RecommendedAction.REPLACE_OR_REQUEST_REVIEW;
        };
        boolean referenceAmbiguous = a.getExtractedReference() == null
                || a.getReferenceConfidence() == null
                || a.getReferenceConfidence().compareTo(ReceiptSubmissionPolicy.LOW_CONFIDENCE) < 0;
        List<BigDecimal> candidates = new ArrayList<>();
        if (a.isAmountAmbiguous() && StringUtils.hasText(a.getAmountCandidates())) {
            for (String part : a.getAmountCandidates().split(",")) {
                candidates.add(new BigDecimal(part));
            }
        }
        return new ReceiptAnalysisResponse(
                a.getId(),
                a.getReadingStatus(),
                a.getReceiptStatus(),
                advice,
                decision.action() != Action.BLOCK_FAILED && decision.action() != Action.BLOCK_PENDING,
                decision.action() == Action.REVIEW_REQUIRED,
                decision.reasons(),
                a.isAmountAmbiguous() ? null : a.getExtractedAmount(),
                a.getExtractedCurrency(),
                a.getExtractedReference(),
                referenceAmbiguous,
                referenceInUse(a),
                a.getExtractedPaidAt() == null ? null : a.getExtractedPaidAt().atZone(ReceiptTextParser.KIGALI).toLocalDate(),
                PaymentAccountService.mask(a.getExtractedSender()),
                PaymentAccountService.mask(a.getExtractedRecipient()),
                a.getRecipientMatch(),
                a.getMeanConfidence(),
                level(a.getMeanConfidence()),
                a.isAmountAmbiguous(),
                List.copyOf(candidates));
    }

    private boolean referenceInUse(ReceiptAnalysis a) {
        if (!StringUtils.hasText(a.getExtractedReference())) {
            return false;
        }
        String normalized;
        try {
            normalized = PaymentReferenceNormalizer.normalize(a.getExtractedReference());
        } catch (BusinessException unusable) {
            return false;
        }
        for (PaymentMethod method : PaymentMethod.values()) {
            if (method == PaymentMethod.CASH) {
                continue;
            }
            if (paymentRepository
                    .findByCooperativeIdAndReferenceKey(a.getCooperativeId(), PaymentReferenceNormalizer.key(method, normalized))
                    .isPresent()) {
                return true;
            }
        }
        return false;
    }

    private static String level(BigDecimal confidence) {
        if (confidence == null) {
            return "UNKNOWN";
        }
        return confidence.compareTo(new BigDecimal("0.8")) >= 0 ? "HIGH" : confidence.compareTo(ReceiptSubmissionPolicy.LOW_CONFIDENCE) >= 0 ? "MEDIUM" : "LOW";
    }
}
