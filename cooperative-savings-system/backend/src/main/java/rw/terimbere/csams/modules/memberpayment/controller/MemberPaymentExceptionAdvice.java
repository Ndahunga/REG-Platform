package rw.terimbere.csams.modules.memberpayment.controller;

import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import rw.terimbere.csams.shared.common.dto.ErrorResponse;
import rw.terimbere.csams.shared.utilities.RequestIdFilter;

/**
 * Turns an unreadable or unknown-field request body of the payment API into a clean 400 with a stable code.
 *
 * <p>Scoped to {@link MemberPaymentController} on purpose: the global handler turns the same exception into a 500
 * for the rest of the API, and changing that is outside this API's scope. Nothing about the parser failure
 * (field names of internal classes, positions) is returned to the caller.
 */
@RestControllerAdvice(
        assignableTypes = {
            MemberPaymentController.class,
            PaymentReversalController.class,
            rw.terimbere.csams.modules.rotation.payout.RotationPayoutController.class
        })
@Order(Ordered.HIGHEST_PRECEDENCE)
public class MemberPaymentExceptionAdvice {

    public static final String INVALID_REQUEST_BODY = "INVALID_REQUEST_BODY";

    private static final Logger log = LoggerFactory.getLogger(MemberPaymentExceptionAdvice.class);

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleUnreadableBody(
            HttpMessageNotReadableException ex, HttpServletRequest request) {
        log.warn("Unreadable payment request body requestId={}: {}", MDC.get(RequestIdFilter.MDC_REQUEST_ID),
                ex.getClass().getSimpleName());
        HttpStatus status = HttpStatus.BAD_REQUEST;
        return ResponseEntity.status(status)
                .body(ErrorResponse.builder()
                        .timestamp(Instant.now())
                        .status(status.value())
                        .error(status.getReasonPhrase())
                        .code(INVALID_REQUEST_BODY)
                        .message("The request body is missing, malformed or contains unsupported fields")
                        .path(request.getRequestURI())
                        .requestId(MDC.get(RequestIdFilter.MDC_REQUEST_ID))
                        .build());
    }
}
