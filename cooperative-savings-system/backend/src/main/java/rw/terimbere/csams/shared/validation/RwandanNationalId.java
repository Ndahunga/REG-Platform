package rw.terimbere.csams.shared.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Rwanda national ID: exactly 16 digits, digits only. Blank/null is allowed (optional field).
 */
@Documented
@Constraint(validatedBy = RwandanNationalIdValidator.class)
@Target({ElementType.FIELD, ElementType.PARAMETER})
@Retention(RetentionPolicy.RUNTIME)
public @interface RwandanNationalId {

    String message() default "National ID must be exactly 16 digits with no letters or spaces";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
