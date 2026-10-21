package rw.terimbere.csams.modules.memberpayment.receipt;

import java.util.ArrayList;
import java.util.List;

/**
 * The words and phrases a receipt uses to say what happened to a payment, kept as data rather than parser logic so a
 * bank or a mobile-money provider with different wording is added by configuration
 * ({@code app.payments.receipt-vocabulary.*}) instead of by rewriting the classifier.
 *
 * <p>Every entry is matched as whole words after normalisation (case, accents and punctuation are ignored), and the
 * longest phrase wins where phrases overlap: "not successful" is one failed phrase, so the word "successful" inside it
 * never counts as success. {@code neutral} phrases consume words without voting for any outcome ("paid to",
 * "processing fee"), and {@code historyMarkers} start the part of a receipt that lists other transactions, which is
 * ignored when deciding what happened to this one.
 */
public record ReceiptVocabulary(
        List<String> success,
        List<String> failed,
        List<String> pending,
        List<String> neutral,
        List<String> historyMarkers) {

    public static ReceiptVocabulary defaults() {
        return new ReceiptVocabulary(
                List.of(
                        "successful", "successfully", "success", "completed", "paid", "transaction successful",
                        "payment successful", "payment completed", "transaction completed", "approved", "yemejwe"),
                List.of(
                        "failed", "unsuccessful", "not successful", "declined", "rejected", "cancelled", "canceled",
                        "transaction failed", "payment failed", "transaction declined", "payment declined", "reversed",
                        "not completed", "not approved", "not paid", "insufficient funds", "byanze",
                        "ntabwo byakunze"),
                List.of("pending", "processing", "awaiting confirmation", "awaiting approval", "in progress"),
                List.of(
                        "paid to", "paid by", "paid with", "paid from", "paid for", "amount paid", "total paid",
                        "processing fee", "processing charge", "pending balance"),
                List.of(
                        "transaction history", "recent transactions", "previous transactions", "last transactions",
                        "mini statement", "account statement", "recent activity"));
    }

    /** These words together with extra deployment-specific ones. */
    public ReceiptVocabulary plus(
            List<String> extraSuccess,
            List<String> extraFailed,
            List<String> extraPending,
            List<String> extraNeutral,
            List<String> extraHistoryMarkers) {
        return new ReceiptVocabulary(
                join(success, extraSuccess),
                join(failed, extraFailed),
                join(pending, extraPending),
                join(neutral, extraNeutral),
                join(historyMarkers, extraHistoryMarkers));
    }

    private static List<String> join(List<String> base, List<String> extra) {
        List<String> all = new ArrayList<>(base);
        if (extra != null) {
            all.addAll(extra);
        }
        return List.copyOf(all);
    }
}
