package rw.terimbere.csams.modules.subscription.payment;

import java.math.BigDecimal;
import java.util.Locale;
import org.springframework.util.StringUtils;
import rw.terimbere.csams.modules.subscription.entity.SubscriptionPayment;
import rw.terimbere.csams.shared.utilities.MoneyUtils;

/**
 * Provider-verified transaction details. SUCCESS is applied only when status, tx_ref,
 * currency, and amount all match the local {@link SubscriptionPayment}.
 */
public record PaymentVerification(
        ProviderPaymentStatus status,
        String txRef,
        String currency,
        BigDecimal amount,
        String mismatchReason) {

    public static PaymentVerification ofStatus(ProviderPaymentStatus status) {
        return new PaymentVerification(status, null, null, null, null);
    }

    public boolean successfulMatch(SubscriptionPayment payment) {
        return status == ProviderPaymentStatus.SUCCESS && mismatchReason(payment) == null;
    }

    /**
     * CARD activation requires a complete provider verification payload, not status alone.
     */
    public boolean verifiedSuccess(SubscriptionPayment payment) {
        if (status != ProviderPaymentStatus.SUCCESS || payment == null) {
            return false;
        }
        if (!StringUtils.hasText(txRef)
                || !StringUtils.hasText(currency)
                || amount == null
                || !StringUtils.hasText(payment.getExternalReference())
                || !StringUtils.hasText(payment.getCurrency())
                || payment.getAmount() == null) {
            return false;
        }
        return mismatchReason(payment) == null;
    }

    public String mismatchReason(SubscriptionPayment payment) {
        if (status != ProviderPaymentStatus.SUCCESS || payment == null) {
            return mismatchReason;
        }
        if (StringUtils.hasText(txRef)
                && StringUtils.hasText(payment.getExternalReference())
                && !txRef.trim().equals(payment.getExternalReference().trim())) {
            return "tx_ref mismatch";
        }
        if (StringUtils.hasText(currency)
                && StringUtils.hasText(payment.getCurrency())
                && !currency.trim().equalsIgnoreCase(payment.getCurrency().trim())) {
            return "currency mismatch";
        }
        if (amount != null && payment.getAmount() != null) {
            BigDecimal expected = MoneyUtils.scale(payment.getAmount());
            BigDecimal actual = MoneyUtils.scale(amount);
            if (expected.compareTo(actual) != 0) {
                return "amount mismatch";
            }
        }
        return mismatchReason;
    }

    public static String normalizeCurrency(String currency) {
        return StringUtils.hasText(currency) ? currency.trim().toUpperCase(Locale.ROOT) : null;
    }
}
