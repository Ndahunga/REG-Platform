package rw.terimbere.csams.shared.exceptions;

import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import rw.terimbere.csams.modules.contact.service.ContactDeliveryException;
import rw.terimbere.csams.shared.common.dto.ErrorResponse;
import rw.terimbere.csams.shared.common.dto.ErrorResponse.FieldErrorDetail;
import rw.terimbere.csams.shared.utilities.RequestIdFilter;

@RestControllerAdvice
public class GlobalExceptionHandler {

    public static final String CONCURRENT_UPDATE_CODE = "CONCURRENT_UPDATE";
    static final String CONCURRENT_UPDATE_MESSAGE =
            "The record was modified by another request. Refresh the data and try again.";

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleNotFound(ResourceNotFoundException ex, HttpServletRequest request) {
        return build(HttpStatus.NOT_FOUND, ex.getMessage(), request, null);
    }

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ErrorResponse> handleBusiness(BusinessException ex, HttpServletRequest request) {
        log.warn("Business exception [{}] requestId={}: {}", ex.getErrorCode(), requestId(), ex.getMessage());
        return build(HttpStatus.UNPROCESSABLE_ENTITY, ex.getMessage(), ex.getErrorCode(), request, null);
    }

    @ExceptionHandler(UnauthorizedException.class)
    public ResponseEntity<ErrorResponse> handleUnauthorized(UnauthorizedException ex, HttpServletRequest request) {
        return build(HttpStatus.UNAUTHORIZED, ex.getMessage(), request, null);
    }

    @ExceptionHandler({AuthenticationException.class, BadCredentialsException.class})
    public ResponseEntity<ErrorResponse> handleAuth(AuthenticationException ex, HttpServletRequest request) {
        return build(HttpStatus.UNAUTHORIZED, "Authentication required", request, null);
    }

    @ExceptionHandler(SubscriptionInactiveException.class)
    public ResponseEntity<ErrorResponse> handleSubscriptionInactive(
            SubscriptionInactiveException ex, HttpServletRequest request) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("cooperativeId", ex.getCooperativeId());
        details.put(
                "subscriptionStatus",
                ex.getSubscriptionStatus() == null ? null : ex.getSubscriptionStatus().name());
        ErrorResponse body = ErrorResponse.builder()
                .timestamp(Instant.now())
                .status(HttpStatus.PAYMENT_REQUIRED.value())
                .error(HttpStatus.PAYMENT_REQUIRED.getReasonPhrase())
                .success(false)
                .code(SubscriptionInactiveException.CODE)
                .message(ex.getMessage())
                .path(request.getRequestURI())
                .requestId(requestId())
                .details(details)
                .build();
        return ResponseEntity.status(HttpStatus.PAYMENT_REQUIRED).body(body);
    }

    @ExceptionHandler(PaymentIntegrationUnavailableException.class)
    public ResponseEntity<ErrorResponse> handlePaymentIntegrationUnavailable(
            PaymentIntegrationUnavailableException ex, HttpServletRequest request) {
        ErrorResponse body = ErrorResponse.builder()
                .timestamp(Instant.now())
                .status(HttpStatus.NOT_IMPLEMENTED.value())
                .error(HttpStatus.NOT_IMPLEMENTED.getReasonPhrase())
                .success(false)
                .code(PaymentIntegrationUnavailableException.CODE)
                .message(ex.getMessage())
                .path(request.getRequestURI())
                .requestId(requestId())
                .build();
        return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED).body(body);
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ErrorResponse> handleMethodNotSupported(
            HttpRequestMethodNotSupportedException ex, HttpServletRequest request) {
        ErrorResponse body = ErrorResponse.builder()
                .timestamp(Instant.now())
                .status(HttpStatus.METHOD_NOT_ALLOWED.value())
                .error(HttpStatus.METHOD_NOT_ALLOWED.getReasonPhrase())
                .success(false)
                .message("This operation is not supported for that HTTP method.")
                .path(request.getRequestURI())
                .requestId(requestId())
                .build();
        ResponseEntity.BodyBuilder response = ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED);
        if (ex.getSupportedHttpMethods() != null && !ex.getSupportedHttpMethods().isEmpty()) {
            response.allow(ex.getSupportedHttpMethods().toArray(new org.springframework.http.HttpMethod[0]));
        }
        return response.body(body);
    }

    @ExceptionHandler(ContactDeliveryException.class)
    public ResponseEntity<ErrorResponse> handleContactDelivery(ContactDeliveryException ex, HttpServletRequest request) {
        ErrorResponse body = ErrorResponse.builder()
                .timestamp(Instant.now())
                .status(HttpStatus.SERVICE_UNAVAILABLE.value())
                .error(HttpStatus.SERVICE_UNAVAILABLE.getReasonPhrase())
                .success(false)
                .code(ContactDeliveryException.CODE)
                .message(ex.getMessage())
                .path(request.getRequestURI())
                .requestId(requestId())
                .build();
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(body);
    }

    @ExceptionHandler({ForbiddenException.class, AccessDeniedException.class})
    public ResponseEntity<ErrorResponse> handleForbidden(RuntimeException ex, HttpServletRequest request) {
        return build(HttpStatus.FORBIDDEN, "Access denied", request, null);
    }

    @ExceptionHandler(ConflictException.class)
    public ResponseEntity<ErrorResponse> handleConflict(ConflictException ex, HttpServletRequest request) {
        return build(HttpStatus.CONFLICT, ex.getMessage(), ex.getCode(), request, null);
    }

    /**
     * Spring translates Hibernate's stale-version failure and JPA's {@code OptimisticLockException} (on repository
     * calls and on commit) into subclasses of {@link OptimisticLockingFailureException}, so that one type covers
     * every {@code @Version} conflict. Pessimistic-lock and other data-access failures are different types and are
     * deliberately not matched. The failed transaction has already rolled back and nothing is retried: the client
     * must reload the record before trying again. The exception message names the entity and its id, so it is
     * neither logged nor returned.
     */
    @ExceptionHandler(OptimisticLockingFailureException.class)
    public ResponseEntity<ErrorResponse> handleOptimisticLock(
            OptimisticLockingFailureException ex, HttpServletRequest request) {
        log.warn(
                "Optimistic lock conflict [{}] requestId={} path={}",
                CONCURRENT_UPDATE_CODE,
                requestId(),
                request.getRequestURI());
        return build(HttpStatus.CONFLICT, CONCURRENT_UPDATE_MESSAGE, CONCURRENT_UPDATE_CODE, request, null);
    }

    @ExceptionHandler(ValidationException.class)
    public ResponseEntity<ErrorResponse> handleValidation(ValidationException ex, HttpServletRequest request) {
        return build(HttpStatus.BAD_REQUEST, ex.getMessage(), ex.getCode(), request, ex.getFieldErrors());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleMethodArgumentNotValid(
            MethodArgumentNotValidException ex, HttpServletRequest request) {
        List<FieldErrorDetail> fieldErrors = ex.getBindingResult().getFieldErrors().stream()
                .map(this::toFieldError)
                .toList();
        return build(HttpStatus.BAD_REQUEST, "Validation failed", request, fieldErrors);
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ErrorResponse> handleTypeMismatch(
            MethodArgumentTypeMismatchException ex, HttpServletRequest request) {
        String name = ex.getName() == null ? "parameter" : ex.getName();
        String message = "Invalid value for " + name;
        if (isDateParam(name)) {
            message = "Invalid date for " + name + ". Use YYYY-MM-DD.";
        } else if (ex.getRequiredType() != null && ex.getRequiredType().isEnum()) {
            message = "Invalid " + name;
        }
        return build(HttpStatus.BAD_REQUEST, message, request, null);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorResponse> handleIllegalArgument(IllegalArgumentException ex, HttpServletRequest request) {
        return build(HttpStatus.BAD_REQUEST, ex.getMessage(), request, null);
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ErrorResponse> handleNoResource(NoResourceFoundException ex, HttpServletRequest request) {
        return build(HttpStatus.NOT_FOUND, "Not found", request, null);
    }

    @ExceptionHandler(HttpMediaTypeNotAcceptableException.class)
    public ResponseEntity<ErrorResponse> handleNotAcceptable(
            HttpMediaTypeNotAcceptableException ex, HttpServletRequest request) {
        return build(HttpStatus.NOT_ACCEPTABLE, "Response format is not acceptable", request, null);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleGeneric(Exception ex, HttpServletRequest request) {
        log.error("Unhandled exception requestId={} path={}", requestId(), request.getRequestURI(), ex);
        return build(HttpStatus.INTERNAL_SERVER_ERROR, "An unexpected error occurred", request, null);
    }

    private FieldErrorDetail toFieldError(FieldError error) {
        return FieldErrorDetail.builder()
                .field(error.getField())
                .message(error.getDefaultMessage())
                .rejectedValue(error.getRejectedValue())
                .build();
    }

    private ResponseEntity<ErrorResponse> build(
            HttpStatus status, String message, HttpServletRequest request, List<FieldErrorDetail> fieldErrors) {
        return build(status, message, null, request, fieldErrors);
    }

    private ResponseEntity<ErrorResponse> build(
            HttpStatus status,
            String message,
            String code,
            HttpServletRequest request,
            List<FieldErrorDetail> fieldErrors) {
        String requestId = requestId();
        ErrorResponse body = ErrorResponse.builder()
                .timestamp(Instant.now())
                .status(status.value())
                .error(status.getReasonPhrase())
                .code(code)
                .message(message)
                .path(request.getRequestURI())
                .requestId(requestId)
                .fieldErrors(fieldErrors)
                .build();
        return ResponseEntity.status(status).body(body);
    }

    private static boolean isDateParam(String name) {
        return "from".equals(name)
                || "to".equals(name)
                || "fromDate".equals(name)
                || "toDate".equals(name);
    }

    private String requestId() {
        return MDC.get(RequestIdFilter.MDC_REQUEST_ID);
    }
}
