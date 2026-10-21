package rw.terimbere.csams.modules.memberpayment.receipt;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import rw.terimbere.csams.modules.memberpayment.entity.ReceiptStatus;

/** The deployment-specific receipt wording is read from configuration and only ever adds to the built-in vocabulary. */
@SpringBootTest(properties = {
    "app.payments.receipt-vocabulary.extra-success=Byakunze neza, Zaba nziza",
    "app.payments.receipt-vocabulary.extra-failed=Ntibyakunze"
})
@ActiveProfiles("test")
class ReceiptVocabularyConfigurationTest {

    @Autowired private ReceiptVocabularyProperties properties;
    @Autowired private ReceiptStatusClassifier classifier;

    @Test
    void configuredPhrasesAreReadTrimmedAndAnUnsetListIsEmpty() {
        assertThat(properties.getExtraSuccess()).containsExactly("Byakunze neza", "Zaba nziza");
        assertThat(properties.getExtraFailed()).containsExactly("Ntibyakunze");
        assertThat(properties.getExtraPending()).isEmpty();
        assertThat(properties.getExtraNeutral()).isEmpty();
        assertThat(properties.getExtraHistoryMarkers()).isEmpty();
    }

    @Test
    void theBuiltInWordsStillApplyAlongsideTheConfiguredOnes() {
        assertThat(classifier.classify("Transaction successful").status()).isEqualTo(ReceiptStatus.SUCCESS_INDICATED);
        assertThat(classifier.classify("Transaction failed").status()).isEqualTo(ReceiptStatus.FAILED_INDICATED);
    }
}
