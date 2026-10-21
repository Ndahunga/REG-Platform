package rw.terimbere.csams.modules.subscription.payment.mtn;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * MTN MoMo Collection HTTP adapter. Tokens and subscription keys are never logged.
 */
public interface MtnMomoClient {

    boolean isConfigured();

    /**
     * Sends a Collection requestToPay. {@code referenceId} is the MTN {@code X-Reference-Id}.
     */
    void requestToPay(UUID referenceId, String msisdn, BigDecimal amount, String currency, String externalId);

    MtnCollectionStatus getRequestToPayStatus(UUID referenceId);
}
