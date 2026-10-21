package rw.terimbere.csams.shared.validation;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class RwandanNationalIdValidatorTest {

    private RwandanNationalIdValidator validator;

    @BeforeEach
    void setUp() {
        validator = new RwandanNationalIdValidator();
        validator.initialize(null);
    }

    @Test
    void acceptsNullAndBlank() {
        assertTrue(validator.isValid(null, null));
        assertTrue(validator.isValid("", null));
        assertTrue(validator.isValid("   ", null));
    }

    @Test
    void acceptsExactlySixteenDigits() {
        assertTrue(validator.isValid("1199780123456789", null));
    }

    @Test
    void rejectsLetters() {
        assertFalse(validator.isValid("119978012345678A", null));
        assertFalse(validator.isValid("ABCDEFGHijklmnop", null));
    }

    @Test
    void rejectsWrongLength() {
        assertFalse(validator.isValid("123456789012345", null));
        assertFalse(validator.isValid("12345678901234567", null));
    }

    @Test
    void rejectsSpacesAndSymbols() {
        assertFalse(validator.isValid("1199 780123456789", null));
        assertFalse(validator.isValid("1199-7801-2345-67", null));
    }
}
