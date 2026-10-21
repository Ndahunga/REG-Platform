package rw.terimbere.csams.modules.memberpayment.receipt;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import rw.terimbere.csams.modules.memberpayment.account.RecipientMatch;
import rw.terimbere.csams.modules.memberpayment.entity.ReceiptStatus;

/**
 * Whether the payer may go on with a payment, given what its receipt says. Pure and deterministic.
 *
 * <ul>
 *   <li>A receipt that says the transaction FAILED, or is still PENDING, stops the submission: there is nothing to
 *       verify yet. The payer is asked for a new receipt (or to wait for the final one).
 *   <li>A receipt that cannot be read, says nothing clear, shows competing amounts, names another recipient, or
 *       disagrees with what the payer typed needs a person: the payer may replace it or ask for a manual review.
 *   <li>Only a clear success-indicated receipt that agrees with the claim lets the payer simply continue.
 *   <li>A deployment that cannot read receipts at all (no OCR engine) is not blocked: officers check by eye, as before.
 * </ul>
 *
 * <p>None of this is proof of settlement. Continuing only means the payment is submitted and waits for an independent
 * verification; nothing is posted.
 */
public final class ReceiptSubmissionPolicy {

    public enum Action {
        PROCEED,
        BLOCK_FAILED,
        BLOCK_PENDING,
        REVIEW_REQUIRED
    }

    /** What the receipt says. */
    public record Facts(
            String readingStatus,
            ReceiptStatus receiptStatus,
            BigDecimal meanConfidence,
            BigDecimal extractedAmount,
            String extractedCurrency,
            String extractedReference,
            BigDecimal referenceConfidence,
            boolean amountAmbiguous,
            RecipientMatch recipientMatch) {}

    /** What the payer says; any part may be absent. */
    public record Claim(BigDecimal amount, String normalizedReference, String schemeCurrency) {
        public static Claim none() {
            return new Claim(null, null, null);
        }
    }

    public record Decision(Action action, List<String> reasons) {}

    static final BigDecimal LOW_CONFIDENCE = new BigDecimal("0.5");

    private ReceiptSubmissionPolicy() {}

    public static Decision decide(Facts f, Claim claim) {
        switch (f.readingStatus()) {
            case ReceiptReader.UNAVAILABLE:
                return new Decision(Action.PROCEED, List.of());
            case ReceiptReader.UNSUPPORTED:
                return new Decision(Action.REVIEW_REQUIRED, List.of("UNSUPPORTED_FILE"));
            case ReceiptReader.COMPLETED:
                break;
            default:
                return new Decision(Action.REVIEW_REQUIRED, List.of("UNREADABLE"));
        }
        if (f.receiptStatus() == ReceiptStatus.FAILED_INDICATED) {
            return new Decision(Action.BLOCK_FAILED, List.of());
        }
        if (f.receiptStatus() == ReceiptStatus.PENDING_INDICATED) {
            return new Decision(Action.BLOCK_PENDING, List.of());
        }
        List<String> reasons = new ArrayList<>();
        if (f.receiptStatus() != ReceiptStatus.SUCCESS_INDICATED) {
            reasons.add("RECEIPT_UNCLEAR");
        }
        if (f.amountAmbiguous()) {
            reasons.add("AMOUNT_AMBIGUOUS");
        } else if (f.extractedAmount() == null) {
            reasons.add("AMOUNT_NOT_FOUND");
        }
        if (f.meanConfidence() != null && f.meanConfidence().compareTo(LOW_CONFIDENCE) < 0) {
            reasons.add("LOW_CONFIDENCE");
        }
        if (f.recipientMatch() == RecipientMatch.MISMATCH) {
            reasons.add("RECIPIENT_MISMATCH");
        }
        Claim c = claim == null ? Claim.none() : claim;
        if (c.schemeCurrency() != null
                && f.extractedCurrency() != null
                && !f.extractedCurrency().equalsIgnoreCase(c.schemeCurrency())) {
            reasons.add("CURRENCY_MISMATCH");
        }
        if (c.amount() != null
                && f.extractedAmount() != null
                && !f.amountAmbiguous()
                && f.extractedAmount().compareTo(c.amount()) != 0) {
            reasons.add("AMOUNT_MISMATCH");
        }
        if (c.normalizedReference() != null
                && f.extractedReference() != null
                && f.referenceConfidence() != null
                && f.referenceConfidence().compareTo(LOW_CONFIDENCE) >= 0
                && !normalize(f.extractedReference()).equals(c.normalizedReference())) {
            reasons.add("REFERENCE_MISMATCH");
        }
        return new Decision(reasons.isEmpty() ? Action.PROCEED : Action.REVIEW_REQUIRED, List.copyOf(reasons));
    }

    private static String normalize(String reference) {
        return reference.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "");
    }
}
