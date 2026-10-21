package rw.terimbere.csams.shared.validation;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class CooperativeFieldRulesTest {

    @Test
    void acceptsRwandanMobileFormats() {
        assertThat(CooperativeFieldRules.isValidRwandanPhone("0781234567")).isTrue();
        assertThat(CooperativeFieldRules.isValidRwandanPhone("078 123 4567")).isTrue();
        assertThat(CooperativeFieldRules.isValidRwandanPhone("+250781234567")).isTrue();
        assertThat(CooperativeFieldRules.normalizePhone("+250 781 234 567")).isEqualTo("0781234567");
        assertThat(CooperativeFieldRules.isValidRwandanPhone("12345")).isFalse();
        assertThat(CooperativeFieldRules.isValidRwandanPhone("254712345678")).isFalse();
    }

    @Test
    void validatesEmail() {
        assertThat(CooperativeFieldRules.isValidEmail("info@terimbere.rw")).isTrue();
        assertThat(CooperativeFieldRules.isValidEmail("not-an-email")).isFalse();
        assertThat(CooperativeFieldRules.isValidEmail(" ")).isFalse();
    }

    @Test
    void validatesRegistrationNumber() {
        assertThat(CooperativeFieldRules.isValidRegistrationNumber("RCA/2024/0123")).isTrue();
        assertThat(CooperativeFieldRules.isValidRegistrationNumber("rca-0123")).isTrue();
        assertThat(CooperativeFieldRules.normalizeRegistrationNumber(" rca / 2024 / 0123 "))
                .isEqualTo("RCA/2024/0123");
        assertThat(CooperativeFieldRules.isValidRegistrationNumber("ab")).isFalse();
        assertThat(CooperativeFieldRules.isValidRegistrationNumber("RCA 2024 0123!!!")).isFalse();
    }

    @Test
    void rejectsFutureRegistrationDate() {
        assertThat(CooperativeFieldRules.isValidRegistrationDate(LocalDate.of(1950, 1, 1))).isTrue();
        assertThat(CooperativeFieldRules.isValidRegistrationDate(LocalDate.now().plusDays(1))).isFalse();
        assertThat(CooperativeFieldRules.isValidRegistrationDate(LocalDate.of(1949, 12, 31))).isFalse();
    }
}
