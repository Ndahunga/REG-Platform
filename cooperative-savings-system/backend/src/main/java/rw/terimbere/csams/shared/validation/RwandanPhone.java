package rw.terimbere.csams.shared.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Documented
@Constraint(validatedBy = RwandanPhoneValidator.class)
@Target({ElementType.FIELD, ElementType.PARAMETER})
@Retention(RetentionPolicy.RUNTIME)
public @interface RwandanPhone {

    String message() default "Phone must be a Rwandan mobile number (10 digits, starting with 07)";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
