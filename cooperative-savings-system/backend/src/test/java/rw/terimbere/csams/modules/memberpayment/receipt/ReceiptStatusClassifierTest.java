package rw.terimbere.csams.modules.memberpayment.receipt;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import rw.terimbere.csams.modules.memberpayment.entity.ReceiptStatus;
import rw.terimbere.csams.modules.memberpayment.receipt.ReceiptTextParser.Parsed;

/** What a receipt claims happened to the payment: the wording, the negations and the receipts that disagree with themselves. */
class ReceiptStatusClassifierTest {

    private final ReceiptStatusClassifier classifier = ReceiptStatusClassifier.withDefaults();

    private ReceiptStatus status(String text) {
        return classifier.classify(text).status();
    }

    @ParameterizedTest
    @CsvSource({
        "Successful, SUCCESS_INDICATED",
        "Payment completed, SUCCESS_INDICATED",
        "Transaction successful, SUCCESS_INDICATED",
        "Yemejwe, SUCCESS_INDICATED",
        "Approved, SUCCESS_INDICATED",
        "Paid, SUCCESS_INDICATED",
        "Failed, FAILED_INDICATED",
        "Unsuccessful, FAILED_INDICATED",
        "Not successful, FAILED_INDICATED",
        "Declined, FAILED_INDICATED",
        "Rejected, FAILED_INDICATED",
        "Cancelled, FAILED_INDICATED",
        "Transaction failed, FAILED_INDICATED",
        "Payment failed, FAILED_INDICATED",
        "Byanze, FAILED_INDICATED",
        "Ntabwo byakunze, FAILED_INDICATED",
        "Pending, PENDING_INDICATED",
        "Processing, PENDING_INDICATED",
        "Awaiting confirmation, PENDING_INDICATED",
        "In progress, PENDING_INDICATED"
    })
    void eachRequiredIndicatorIsClassifiedAsItsOutcome(String text, ReceiptStatus expected) {
        assertThat(status(text)).isEqualTo(expected);
    }

    @Test
    void aNegatedSuccessWordIsAFailureNotASuccess() {
        assertThat(status("The transaction was NOT successful")).isEqualTo(ReceiptStatus.FAILED_INDICATED);
        assertThat(status("Not   SUCCESSFUL")).isEqualTo(ReceiptStatus.FAILED_INDICATED);
        assertThat(status("not\nsuccessful")).as("a line break inside the phrase").isEqualTo(ReceiptStatus.FAILED_INDICATED);
        assertThat(status("Payment was not completed")).isEqualTo(ReceiptStatus.FAILED_INDICATED);
        assertThat(status("Request not approved")).isEqualTo(ReceiptStatus.FAILED_INDICATED);
        assertThat(status("Unsuccessful")).as("never the word 'successful'").isEqualTo(ReceiptStatus.FAILED_INDICATED);
    }

    @Test
    void caseAccentsPunctuationAndSpacingDoNotMatter() {
        assertThat(status("TRANSACTION   SUCCESSFUL!!!")).isEqualTo(ReceiptStatus.SUCCESS_INDICATED);
        assertThat(status("transaction-successful")).isEqualTo(ReceiptStatus.SUCCESS_INDICATED);
        assertThat(status("Status: FAILED.")).isEqualTo(ReceiptStatus.FAILED_INDICATED);
        assertThat(status("Ntabwo   Byakunze")).isEqualTo(ReceiptStatus.FAILED_INDICATED);
        assertThat(status("Yêmejwé")).as("accents are folded").isEqualTo(ReceiptStatus.SUCCESS_INDICATED);
    }

    @Test
    void wordsOnlyMatchWholeAndNeverInsideOtherWords() {
        assertThat(status("Unsuccessfully")).as("not the word 'success'").isEqualTo(ReceiptStatus.UNKNOWN);
        assertThat(status("Prepaid bundle")).as("'paid' inside another word").isEqualTo(ReceiptStatus.UNKNOWN);
    }

    @Test
    void contradictoryWordingForTheSameTransactionIsUnknownAndNeedsAPerson() {
        ReceiptStatusClassifier.Classification mixed = classifier.classify("Payment successful\nTransaction failed\nAmount: 500");
        assertThat(mixed.status()).isEqualTo(ReceiptStatus.UNKNOWN);
        assertThat(mixed.indicators()).hasSize(2);
        assertThat(status("Pending then completed")).isEqualTo(ReceiptStatus.UNKNOWN);
    }

    @Test
    void noIndicatorAtAllIsUnknownNeverSuccess() {
        assertThat(status("")).isEqualTo(ReceiptStatus.UNKNOWN);
        assertThat(status(null)).isEqualTo(ReceiptStatus.UNKNOWN);
        assertThat(status("Amount 80,000 RWF sent to 0788123456")).isEqualTo(ReceiptStatus.UNKNOWN);
        assertThat(classifier.classify("Amount 80,000").strength()).isZero();
    }

    @Test
    void wordsThatAreNotAboutTheOutcomeDoNotVote() {
        assertThat(status("Transaction successful. Paid to MTN Rwanda, processing fee RWF 100"))
                .as("'paid to' and 'processing fee' are neutral")
                .isEqualTo(ReceiptStatus.SUCCESS_INDICATED);
        assertThat(status("Failed. Paid to 0788123456")).isEqualTo(ReceiptStatus.FAILED_INDICATED);
    }

    @Test
    void aFailedTransactionListedInTheHistoryDoesNotChangeThisOnesOutcome() {
        String receipt = "Payment successful\nAmount: RWF 80,000\nRef: ABC123456\n\n"
                + "Recent transactions\n03/03/2026 RWF 5,000 Failed\n02/03/2026 RWF 2,000 Declined";
        assertThat(status(receipt)).isEqualTo(ReceiptStatus.SUCCESS_INDICATED);

        String failedWithSuccessfulHistory = "Transaction failed\nTransaction history\n01/03/2026 RWF 5,000 Successful";
        assertThat(status(failedWithSuccessfulHistory)).isEqualTo(ReceiptStatus.FAILED_INDICATED);
    }

    @Test
    void aDeploymentCanAddItsOwnWordingWithoutTouchingTheClassifier() {
        ReceiptVocabulary vocabulary = ReceiptVocabulary.defaults()
                .plus(List.of("byagenze neza"), List.of("ntibyagenze"), List.of("birategerejwe"), List.of(), List.of());
        ReceiptStatusClassifier custom = new ReceiptStatusClassifier(vocabulary);
        assertThat(custom.classify("Byagenze neza").status()).isEqualTo(ReceiptStatus.SUCCESS_INDICATED);
        assertThat(custom.classify("Ntibyagenze").status()).isEqualTo(ReceiptStatus.FAILED_INDICATED);
        assertThat(custom.classify("Birategerejwe").status()).isEqualTo(ReceiptStatus.PENDING_INDICATED);
        assertThat(classifier.classify("Byagenze neza").status()).as("the built-in classifier is unchanged").isEqualTo(ReceiptStatus.UNKNOWN);
    }

    @Test
    void theParserReportsTheClassifiedStatusWithConfidenceScaledByTheReading() {
        Parsed failed = ReceiptTextParser.parse("Transaction not successful\nAmount: 500", 0.9);
        assertThat(failed.status().value()).isEqualTo(ReceiptStatus.FAILED_INDICATED);
        assertThat(failed.status().confidence()).isEqualTo(0.72);
        Parsed none = ReceiptTextParser.parse("Amount: 500", 0.9);
        assertThat(none.status().value()).isEqualTo(ReceiptStatus.UNKNOWN);
    }
}
