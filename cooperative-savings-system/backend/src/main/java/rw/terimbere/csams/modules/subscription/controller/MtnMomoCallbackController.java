package rw.terimbere.csams.modules.subscription.controller;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.swagger.v3.oas.annotations.Hidden;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import rw.terimbere.csams.modules.subscription.service.BillingService;

/**
 * Unauthenticated MTN Collection callback. The payload is never trusted; BillingService
 * re-queries MTN before changing entitlement. Always returns 200 so MTN does not retry
 * storms against unknown references. Malformed bodies never expose stack traces.
 */
@RestController
@RequestMapping("/api/v1/public/billing/mtn")
@RequiredArgsConstructor
@Tag(name = "Billing callbacks", description = "MTN Mobile Money collection callbacks")
public class MtnMomoCallbackController {

    private static final Logger log = LoggerFactory.getLogger(MtnMomoCallbackController.class);

    private final BillingService billingService;

    @PostMapping("/callback")
    @Operation(summary = "MTN MoMo collection callback")
    public ResponseEntity<Void> callback(@RequestBody(required = false) CallbackBody body) {
        accept(body == null ? null : body.reference());
        return ResponseEntity.ok().build();
    }

    @Hidden
    @PostMapping("/callback/{referenceId}")
    public ResponseEntity<Void> callbackWithReference(
            @PathVariable String referenceId, @RequestBody(required = false) CallbackBody body) {
        String reference = StringUtils.hasText(referenceId)
                ? referenceId
                : (body == null ? null : body.reference());
        accept(reference);
        return ResponseEntity.ok().build();
    }

    private void accept(String reference) {
        try {
            log.info("MTN MoMo callback received");
            billingService.handleMtnCallback(reference);
        } catch (RuntimeException ex) {
            log.warn("MTN MoMo callback processing failed: {}", ex.getClass().getSimpleName());
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CallbackBody(String externalId, String status, Map<String, Object> payer) {
        String reference() {
            return externalId;
        }
    }
}
