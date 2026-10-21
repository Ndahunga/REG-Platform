package rw.terimbere.csams.modules.report.whatsapp;

/**
 * Official WhatsApp Cloud API client. Implementations must never log access tokens.
 */
public interface WhatsAppCloudClient {

    void sendDocument(String recipient, byte[] pdf, String filename, String caption);

    /** Session text message. Must not use the report document template. */
    void sendText(String recipient, String body);
}
