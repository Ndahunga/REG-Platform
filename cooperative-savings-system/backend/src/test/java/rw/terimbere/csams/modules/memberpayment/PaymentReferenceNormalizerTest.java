package rw.terimbere.csams.modules.memberpayment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import rw.terimbere.csams.modules.memberpayment.entity.PaymentMethod;
import rw.terimbere.csams.modules.memberpayment.service.PaymentReferenceNormalizer;
import rw.terimbere.csams.shared.exceptions.BusinessException;

class PaymentReferenceNormalizerTest {

    @Test
    void ignoresCaseWhitespaceAndPunctuation() {
        assertThat(PaymentReferenceNormalizer.normalize(" mp-240101 / 123 ")).isEqualTo("MP240101123");
        assertThat(PaymentReferenceNormalizer.normalize("MP240101123")).isEqualTo("MP240101123");
    }

    @Test
    void keepsLeadingZerosSoDifferentTransactionsAreNotMerged() {
        assertThat(PaymentReferenceNormalizer.normalize("000123")).isEqualTo("000123");
        assertThat(PaymentReferenceNormalizer.normalize("000123"))
                .isNotEqualTo(PaymentReferenceNormalizer.normalize("123"));
    }

    @Test
    void blankReferenceIsAbsentNotAnError() {
        assertThat(PaymentReferenceNormalizer.normalize(null)).isNull();
        assertThat(PaymentReferenceNormalizer.normalize("   ")).isNull();
    }

    @Test
    void referenceWithNoLettersOrDigitsIsRejected() {
        assertThatThrownBy(() -> PaymentReferenceNormalizer.normalize("--- ///"))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void overlongReferenceIsRejectedNotTruncated() {
        assertThatThrownBy(() -> PaymentReferenceNormalizer.normalize("A".repeat(129)))
                .isInstanceOf(BusinessException.class);
        assertThat(PaymentReferenceNormalizer.normalize("A".repeat(128))).hasSize(128);
    }

    @Test
    void keyIsScopedByPaymentMethod() {
        assertThat(PaymentReferenceNormalizer.key(PaymentMethod.MTN_MOMO, "ABC1"))
                .isNotEqualTo(PaymentReferenceNormalizer.key(PaymentMethod.BANK_TRANSFER, "ABC1"));
        assertThat(PaymentReferenceNormalizer.key(PaymentMethod.MTN_MOMO, null)).isNull();
    }
}
