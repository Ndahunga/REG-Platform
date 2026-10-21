package rw.terimbere.csams.shared.exceptions;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import lombok.Getter;
import rw.terimbere.csams.shared.common.dto.ErrorResponse.FieldErrorDetail;

@Getter
public class ValidationException extends RuntimeException {

    private final List<FieldErrorDetail> fieldErrors;

    /** A stable code the client can translate; null for the ordinary field-validation failures. */
    private final String code;

    /** A validation failure with a stable code (the message stays in English for logs and diagnostics). */
    public static ValidationException coded(String code, String message) {
        return new ValidationException(code, message, true);
    }

    private ValidationException(String code, String message, boolean coded) {
        super(message);
        this.fieldErrors = Collections.emptyList();
        this.code = code;
    }

    public ValidationException(String message) {
        super(message);
        this.fieldErrors = Collections.emptyList();
        this.code = null;
    }

    public ValidationException(String message, List<FieldErrorDetail> fieldErrors) {
        super(message);
        this.code = null;
        this.fieldErrors = fieldErrors == null ? Collections.emptyList() : List.copyOf(fieldErrors);
    }

    public ValidationException(String message, Map<String, String> errors) {
        super(message);
        this.code = null;
        this.fieldErrors = errors == null
                ? Collections.emptyList()
                : errors.entrySet().stream()
                        .map(e -> FieldErrorDetail.builder()
                                .field(e.getKey())
                                .message(e.getValue())
                                .build())
                        .toList();
    }
}
