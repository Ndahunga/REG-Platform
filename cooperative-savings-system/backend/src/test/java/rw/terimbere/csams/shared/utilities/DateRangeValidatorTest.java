package rw.terimbere.csams.shared.utilities;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import rw.terimbere.csams.shared.exceptions.ValidationException;

class DateRangeValidatorTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 8, 26);

    @Test
    void acceptsEmptyOrSameDayRange() {
        assertDoesNotThrow(() -> DateRangeValidator.validateOptional(null, null, TODAY));
        assertDoesNotThrow(() -> DateRangeValidator.validateOptional(TODAY, TODAY, TODAY));
        assertDoesNotThrow(
                () -> DateRangeValidator.validateOptional(TODAY.minusDays(10), TODAY, TODAY));
    }

    @Test
    void rejectsFromAfterTo() {
        assertThatThrownBy(() -> DateRangeValidator.validateOptional(
                        TODAY, TODAY.minusDays(1), TODAY))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("Invalid date range");
    }

    @Test
    void rejectsFutureDates() {
        assertThatThrownBy(() -> DateRangeValidator.validateOptional(TODAY.plusDays(1), null, TODAY))
                .isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> DateRangeValidator.validateOptional(null, TODAY.plusDays(1), TODAY))
                .isInstanceOf(ValidationException.class);
    }
}
