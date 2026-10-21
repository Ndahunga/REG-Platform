package rw.terimbere.csams.shared.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

public class RwandanPhoneValidator implements ConstraintValidator<RwandanPhone, String> {

    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        if (value == null || value.isBlank()) {
            return true;
        }
        return CooperativeFieldRules.isValidRwandanPhone(value);
    }
}
