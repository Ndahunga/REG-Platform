package rw.terimbere.csams.shared.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Documented
@Constraint(validatedBy = CooperativeRegistrationNumberValidator.class)
@Target({ElementType.FIELD, ElementType.PARAMETER})
@Retention(RetentionPolicy.RUNTIME)
public @interface CooperativeRegistrationNumber {

    String message() default
            "Registration number must be 4–32 characters (letters, digits, / or -), e.g. RCA/2024/0123";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
