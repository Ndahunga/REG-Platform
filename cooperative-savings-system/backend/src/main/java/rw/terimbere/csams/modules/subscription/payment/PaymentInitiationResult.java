package rw.terimbere.csams.modules.subscription.payment;

public record PaymentInitiationResult(
        boolean accepted, String externalReference, String checkoutUrl, String failureMessage) {

    public static PaymentInitiationResult accepted(String externalReference) {
        return new PaymentInitiationResult(true, externalReference, null, null);
    }

    public static PaymentInitiationResult hosted(String externalReference, String checkoutUrl) {
        return new PaymentInitiationResult(true, externalReference, checkoutUrl, null);
    }

    public static PaymentInitiationResult rejected(String failureMessage) {
        return new PaymentInitiationResult(false, null, null, failureMessage);
    }
}
