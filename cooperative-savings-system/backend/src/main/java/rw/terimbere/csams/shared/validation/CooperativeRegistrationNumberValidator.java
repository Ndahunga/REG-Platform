package rw.terimbere.csams.shared.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

public class CooperativeRegistrationNumberValidator
        implements ConstraintValidator<CooperativeRegistrationNumber, String> {

    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        if (value == null || value.isBlank()) {
            return true;
        }
        return CooperativeFieldRules.isValidRegistrationNumber(value);
    }
}
