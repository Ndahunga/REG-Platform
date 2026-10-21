package rw.terimbere.csams.shared.exceptions;

public class ConflictException extends RuntimeException {

    /** Optional stable machine-readable code (null for legacy callers). Returned in {@code ErrorResponse.code}. */
    private final String code;

    public ConflictException(String message) {
        this(null, message);
    }

    public ConflictException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
