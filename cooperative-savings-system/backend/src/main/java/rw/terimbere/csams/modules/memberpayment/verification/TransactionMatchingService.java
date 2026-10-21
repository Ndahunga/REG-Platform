package rw.terimbere.csams.modules.memberpayment.verification;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import rw.terimbere.csams.modules.memberpayment.entity.MemberPayment;
import rw.terimbere.csams.modules.memberpayment.entity.PaymentMethod;
import rw.terimbere.csams.modules.memberpayment.entity.VerificationMethod;
import rw.terimbere.csams.modules.memberpayment.receipt.PaymentReceiptExtraction;
import rw.terimbere.csams.modules.memberpayment.receipt.PaymentReceiptExtractionRepository;
import rw.terimbere.csams.modules.memberpayment.receipt.ReceiptExtractionService;

/**
 * Decides what the trusted evidence says about a payment. It only reads: recording the decision is the job of
 * {@link PaymentVerificationCheckService}, and acting on it (verifying, posting) is always an officer's.
 *
 * <p>Matching is by channel and normalized reference inside the payment's own Saving Scheme, so evidence of another
 * scheme can never match. The decision is CONFIRMED only for a trusted transaction that reports SUCCESS and agrees on
 * amount and currency and was not already used. Anything the receipt image says can only add a conflict; it never
 * contributes to a confirmation.
 */
@Service
@RequiredArgsConstructor
public class TransactionMatchingService {

    /** A receipt field is only held against trusted evidence when it was read with at least this confidence. */
    static final double RECEIPT_CONFLICT_CONFIDENCE = 0.7;

    private final TrustedTransactionRepository transactionRepository;
    private final PaymentReceiptExtractionRepository extractionRepository;
    private final ObjectMapper objectMapper;

    public record Decision(
            VerificationOutcome outcome,
            VerificationMethod method,
            VerificationSource source,
            UUID trustedTransactionId,
            List<String> reasons,
            String detail) {}

    public Decision evaluate(MemberPayment payment) {
        if (payment.getPaymentMethod() == PaymentMethod.CASH) {
            return new Decision(
                    VerificationOutcome.PENDING,
                    VerificationMethod.CASH_RECEIPT_CONFIRMED,
                    VerificationSource.REFERENCE_MATCH,
                    null,
                    List.of("AWAITING_CASH_CONFIRMATION"),
                    "Cash is confirmed only by an authorized officer who received it");
        }
        VerificationMethod defaultMethod = defaultMethod(payment.getPaymentMethod());
        String reference = payment.getNormalizedReference();
        if (reference == null) {
            return unmatched(defaultMethod, "NO_REFERENCE", "The payment has no transaction reference to match");
        }
        TrustedTransaction tx = transactionRepository
                .findByCooperativeIdAndChannelAndNormalizedReference(
                        payment.getCooperativeId(), payment.getPaymentMethod(), reference)
                .orElse(null);
        if (tx == null) {
            return unmatched(
                    defaultMethod,
                    "NO_TRUSTED_RECORD",
                    "No imported statement line or provider confirmation matches this reference yet");
        }
        VerificationMethod method = methodOf(tx.getSource());
        List<String> conflicts = new ArrayList<>();
        if (!method.fits(payment.getPaymentMethod())) {
            conflicts.add("METHOD_MISMATCH");
        }
        if (tx.isConflicted()) {
            conflicts.add("CONFLICTING_EVIDENCE");
        }
        if (tx.getConsumedByPaymentId() != null && !tx.getConsumedByPaymentId().equals(payment.getId())) {
            conflicts.add("TRANSACTION_ALREADY_USED");
        }
        if (tx.getAmount().compareTo(payment.getAmount()) != 0) {
            conflicts.add("AMOUNT_MISMATCH");
        }
        if (!tx.getCurrency().equalsIgnoreCase(payment.getCurrency())) {
            conflicts.add("CURRENCY_MISMATCH");
        }
        addReceiptConflicts(payment, tx, conflicts);
        if (!conflicts.isEmpty()) {
            return new Decision(
                    VerificationOutcome.CONFLICTED,
                    method,
                    tx.getSource(),
                    tx.getId(),
                    conflicts,
                    "The evidence disagrees: " + String.join(", ", conflicts));
        }
        return switch (tx.getProviderStatus()) {
            case SUCCESS -> new Decision(
                    VerificationOutcome.CONFIRMED,
                    method,
                    tx.getSource(),
                    tx.getId(),
                    List.of(),
                    "Matched a trusted record that reports success");
            case FAILED -> new Decision(
                    VerificationOutcome.FAILED,
                    method,
                    tx.getSource(),
                    tx.getId(),
                    List.of("PROVIDER_REPORTED_FAILURE"),
                    "The trusted record reports that the transaction failed");
            case PENDING -> new Decision(
                    VerificationOutcome.PENDING,
                    method,
                    tx.getSource(),
                    tx.getId(),
                    List.of("PROVIDER_REPORTED_PENDING"),
                    "The trusted record reports that the transaction has not settled");
        };
    }

    /** What the receipt image claims, held against the trusted record. Only confidently read fields count. */
    private void addReceiptConflicts(MemberPayment payment, TrustedTransaction tx, List<String> conflicts) {
        for (PaymentReceiptExtraction e :
                extractionRepository.findByCooperativeIdAndPaymentIdOrderByCreatedAtAsc(
                        payment.getCooperativeId(), payment.getId())) {
            if (!ReceiptExtractionService.COMPLETED.equals(e.getStatus())) {
                continue;
            }
            Map<String, Double> confidence = confidences(e);
            if (e.getExtractedRecipient() != null
                    && tx.getRecipient() != null
                    && confident(confidence, "recipient")
                    && !digits(e.getExtractedRecipient()).equals(digits(tx.getRecipient()))
                    && !conflicts.contains("WRONG_RECIPIENT")) {
                conflicts.add("WRONG_RECIPIENT");
            }
            if (e.getExtractedAmount() != null
                    && confident(confidence, "amount")
                    && e.getExtractedAmount().compareTo(tx.getAmount()) != 0
                    && !conflicts.contains("RECEIPT_AMOUNT_CONFLICT")) {
                conflicts.add("RECEIPT_AMOUNT_CONFLICT");
            }
        }
    }

    private Map<String, Double> confidences(PaymentReceiptExtraction e) {
        if (e.getFieldConfidences() == null) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(e.getFieldConfidences(), new TypeReference<LinkedHashMap<String, Double>>() {});
        } catch (JsonProcessingException ex) {
            return Map.of();
        }
    }

    private static boolean confident(Map<String, Double> confidence, String field) {
        Double c = confidence.get(field);
        return c != null && c >= RECEIPT_CONFLICT_CONFIDENCE;
    }

    private static String digits(String value) {
        return value.replaceAll("\\D", "");
    }

    private static Decision unmatched(VerificationMethod method, String reason, String detail) {
        return new Decision(
                VerificationOutcome.UNMATCHED, method, VerificationSource.REFERENCE_MATCH, null, List.of(reason), detail);
    }

    /** How a trusted record of this source confirms a payment. */
    public static VerificationMethod methodOf(VerificationSource source) {
        return switch (source) {
            case BANK_STATEMENT_IMPORT -> VerificationMethod.BANK_RECONCILED;
            case MOBILE_MONEY_STATEMENT_IMPORT -> VerificationMethod.MOBILE_MONEY_RECONCILED;
            case PROVIDER_CALLBACK -> VerificationMethod.PROVIDER_CONFIRMED;
            default -> throw new IllegalArgumentException(source + " is not a source of trusted transactions");
        };
    }

    private static VerificationMethod defaultMethod(PaymentMethod method) {
        return switch (method) {
            case BANK_TRANSFER -> VerificationMethod.BANK_RECONCILED;
            case MTN_MOMO, AIRTEL_MONEY -> VerificationMethod.MOBILE_MONEY_RECONCILED;
            case CASH -> VerificationMethod.CASH_RECEIPT_CONFIRMED;
            case OTHER -> VerificationMethod.PROVIDER_CONFIRMED;
        };
    }
}
