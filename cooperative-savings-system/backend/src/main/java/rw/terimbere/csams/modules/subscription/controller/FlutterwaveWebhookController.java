package rw.terimbere.csams.modules.subscription.controller;

import io.swagger.v3.oas.annotations.Hidden;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import rw.terimbere.csams.modules.subscription.payment.flutterwave.FlutterwaveWebhookPayload;
import rw.terimbere.csams.modules.subscription.service.BillingService;
import rw.terimbere.csams.shared.exceptions.UnauthorizedException;

/**
 * Unauthenticated Flutterwave webhook. Signature is checked first; invalid hashes never
 * reach provider verification. Payload amounts/status are never authoritative.
 */
@RestController
@RequestMapping("/api/v1/public/billing/flutterwave")
@RequiredArgsConstructor
@Tag(name = "Billing callbacks", description = "Flutterwave hosted-checkout webhooks")
public class FlutterwaveWebhookController {

    private static final Logger log = LoggerFactory.getLogger(FlutterwaveWebhookController.class);

    private final BillingService billingService;

    @PostMapping("/webhook")
    @Operation(summary = "Flutterwave charge webhook")
    @Hidden
    public ResponseEntity<Void> webhook(
            @RequestHeader(value = "verif-hash", required = false) String verifHash,
            @RequestBody(required = false) FlutterwaveWebhookPayload body) {
        try {
            billingService.handleFlutterwaveWebhook(verifHash, body);
            return ResponseEntity.ok().build();
        } catch (UnauthorizedException ex) {
            log.warn("Flutterwave webhook rejected: invalid signature");
            throw ex;
        } catch (RuntimeException ex) {
            log.warn("Flutterwave webhook processing failed: {}", ex.getClass().getSimpleName());
            return ResponseEntity.ok().build();
        }
    }
}
