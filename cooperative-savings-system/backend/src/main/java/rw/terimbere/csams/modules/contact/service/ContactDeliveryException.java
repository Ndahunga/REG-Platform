package rw.terimbere.csams.modules.contact.service;

/** The contact message could not be handed to the mail server. Carries no provider details on purpose. */
public class ContactDeliveryException extends RuntimeException {

    public static final String CODE = "CONTACT_DELIVERY_FAILED";

    private static final String MESSAGE =
            "We could not send your message right now. Please try again or contact us by email or WhatsApp.";

    public ContactDeliveryException() {
        super(MESSAGE);
    }

    public ContactDeliveryException(Throwable cause) {
        super(MESSAGE, cause);
    }
}
