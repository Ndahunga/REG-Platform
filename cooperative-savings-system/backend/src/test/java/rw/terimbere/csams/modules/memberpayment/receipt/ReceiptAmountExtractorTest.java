package rw.terimbere.csams.modules.memberpayment.receipt;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import rw.terimbere.csams.modules.memberpayment.receipt.ReceiptAmountExtractor.Reading;
import rw.terimbere.csams.modules.memberpayment.receipt.ReceiptTextParser.Parsed;

/** The payment amount is the amount of the payment: not a fee, not a balance, not a phone number, never a guess. */
class ReceiptAmountExtractorTest {

    @Test
    void thePaymentIsChosenOverTheFeeAndTheBalance() {
        Reading r = ReceiptAmountExtractor.extract("Payment: RWF 80,000\nFee: RWF 500\nBalance: RWF 350,000");
        assertThat(r.amount()).isEqualByComparingTo("80000");
        assertThat(r.currency()).isEqualTo("RWF");
        assertThat(r.ambiguous()).isFalse();
    }

    @Test
    void aBalanceOnTheSameLineIsNotThePayment() {
        Reading r = ReceiptAmountExtractor.extract("You have sent RWF 80,000 to 0788123456. Fee RWF 500. New balance: RWF 350,000.");
        assertThat(r.amount()).isEqualByComparingTo("80000");
        assertThat(r.ambiguous()).isFalse();
        Reading before = ReceiptAmountExtractor.extract("Available balance RWF 350,000 Amount RWF 80,000");
        assertThat(before.amount()).isEqualByComparingTo("80000");
    }

    @Test
    void anAmountAndATotalThatIncludesTheStatedFeeResolveToTheAmountWithoutTheFee() {
        Reading r = ReceiptAmountExtractor.extract("Amount: 80,000 RWF\nFee: 500 RWF\nTotal: 80,500 RWF");
        assertThat(r.amount()).isEqualByComparingTo("80000");
        assertThat(r.ambiguous()).isFalse();
        assertThat(r.strength()).isLessThan(0.9);
    }

    @Test
    void competingPaymentAmountsAreAmbiguousAndNothingIsChosen() {
        Reading r = ReceiptAmountExtractor.extract("Amount: 80,000\nAmount: 75,000");
        assertThat(r.ambiguous()).isTrue();
        assertThat(r.amount()).isNull();
        assertThat(r.candidates()).containsExactlyInAnyOrder(new BigDecimal("80000"), new BigDecimal("75000"));

        Reading totalWithoutFee = ReceiptAmountExtractor.extract("Amount: 80,000\nTotal: 81,000");
        assertThat(totalWithoutFee.ambiguous()).as("no stated fee explains the difference").isTrue();
        assertThat(totalWithoutFee.amount()).isNull();
    }

    @Test
    void aReceiptThatOnlyShowsABalanceOrAFeeHasNoPaymentAmount() {
        assertThat(ReceiptAmountExtractor.extract("Balance: RWF 350,000").amount()).isNull();
        assertThat(ReceiptAmountExtractor.extract("Transaction fee RWF 500\nDaily limit RWF 2,000,000").amount()).isNull();
        assertThat(ReceiptAmountExtractor.extract("").amount()).isNull();
    }

    @Test
    void phoneNumbersReferencesAndDatesAreNeverAmounts() {
        Reading r = ReceiptAmountExtractor.extract(
                "Financial Transaction Id: 4418293847\nPayment reference: 12345678\nAmount: 15,000\nTo: 0788123456\nDate: 2026-03-05 14:32:10");
        assertThat(r.amount()).isEqualByComparingTo("15000");
        assertThat(r.ambiguous()).isFalse();
    }

    @Test
    void aBareAmountNextToACurrencyIsAWeakButUsableReading() {
        Reading r = ReceiptAmountExtractor.extract("MTN MoMo\nRWF 15,000\nsuccessful");
        assertThat(r.amount()).isEqualByComparingTo("15000");
        assertThat(r.strength()).isLessThan(0.7);
        Reading two = ReceiptAmountExtractor.extract("RWF 15,000\nRWF 16,000");
        assertThat(two.ambiguous()).isTrue();
        assertThat(two.amount()).isNull();
    }

    @Test
    void groupingDecimalsAndCurrencyOrderAreUnderstood() {
        assertThat(ReceiptAmountExtractor.extract("Amount: 1 250 000.50 RWF").amount()).isEqualByComparingTo("1250000.50");
        assertThat(ReceiptAmountExtractor.extract("Amount paid FRW 30000").amount()).isEqualByComparingTo("30000");
        assertThat(ReceiptAmountExtractor.extract("Amount paid FRW 30000").currency()).isEqualTo("RWF");
    }

    @Test
    void theParserCarriesTheAmbiguityAndNeverFillsInARandomAmount() {
        Parsed clear = ReceiptTextParser.parse("Payment: RWF 80,000\nFee: RWF 500\nBalance: RWF 350,000\nSuccessful", 0.9);
        assertThat(clear.amount().value()).isEqualByComparingTo("80000");
        assertThat(clear.amountAmbiguous()).isFalse();

        Parsed unclear = ReceiptTextParser.parse("Amount: 80,000\nAmount: 75,000\nSuccessful", 0.9);
        assertThat(unclear.amount()).isNull();
        assertThat(unclear.amountAmbiguous()).isTrue();
        assertThat(unclear.amountCandidates()).hasSize(2);
        assertThat(unclear.currency()).as("currency is still unknown without a code").isNull();
    }
}
