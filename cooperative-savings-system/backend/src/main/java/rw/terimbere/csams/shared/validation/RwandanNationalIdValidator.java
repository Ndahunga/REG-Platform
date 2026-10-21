package rw.terimbere.csams.shared.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

public class RwandanNationalIdValidator implements ConstraintValidator<RwandanNationalId, String> {

    private static final String PATTERN = "^\\d{16}$";

    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        if (value == null || value.isBlank()) {
            return true;
        }
        return value.trim().matches(PATTERN);
    }
}
