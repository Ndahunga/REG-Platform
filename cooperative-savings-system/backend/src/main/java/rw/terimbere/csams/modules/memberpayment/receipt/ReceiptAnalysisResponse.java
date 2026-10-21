package rw.terimbere.csams.modules.memberpayment.receipt;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import rw.terimbere.csams.modules.memberpayment.account.RecipientMatch;
import rw.terimbere.csams.modules.memberpayment.entity.ReceiptStatus;

/**
 * What a member is told about the receipt they just uploaded, before they submit the payment. It carries only safe,
 * member-facing facts: the amount, reference and date the receipt appears to show, the sender and recipient hidden down
 * to their last digits, and what to do next. It never exposes the receipt text or any internal detail, and it is a claim
 * made by an image, never confirmation that money arrived.
 *
 * @param reading            COMPLETED when the receipt could be read; FAILED, UNSUPPORTED or UNAVAILABLE otherwise
 * @param receiptStatus      what the receipt claims happened (null when it could not be read)
 * @param recommendedAction  what the member should do next
 * @param canSubmit          false when the receipt says the transaction failed or is still pending
 * @param manualReviewNeeded true when the member may only continue by asking for a manual review (or by replacing the receipt)
 * @param reviewReasons      stable codes saying why a review is needed
 * @param amountCandidates   the competing payment amounts, only when the receipt showed more than one
 */
public record ReceiptAnalysisResponse(
        UUID analysisId,
        String reading,
        ReceiptStatus receiptStatus,
        RecommendedAction recommendedAction,
        boolean canSubmit,
        boolean manualReviewNeeded,
        List<String> reviewReasons,
        BigDecimal extractedAmount,
        String currency,
        String extractedReference,
        boolean referenceAmbiguous,
        boolean referenceAlreadyUsed,
        LocalDate extractedDate,
        String apparentSender,
        String apparentRecipient,
        RecipientMatch recipientMatch,
        BigDecimal confidence,
        String confidenceLevel,
        boolean amountAmbiguous,
        List<BigDecimal> amountCandidates) {

    public enum RecommendedAction {
        /** The receipt looks successful and agrees with the claim: review the details and submit. */
        CONTINUE,
        /** The receipt says the transaction failed: pay again and upload the new confirmation. */
        REPLACE_RECEIPT,
        /** The receipt says the transaction is still processing: upload the final confirmation when it is done. */
        WAIT_FOR_FINAL_RECEIPT,
        /** The receipt is unclear: upload a clearer one, or submit for a manual review by an officer. */
        REPLACE_OR_REQUEST_REVIEW
    }
}
