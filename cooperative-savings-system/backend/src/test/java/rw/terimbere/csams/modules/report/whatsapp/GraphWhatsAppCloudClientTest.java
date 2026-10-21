package rw.terimbere.csams.modules.report.whatsapp;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import rw.terimbere.csams.shared.exceptions.BusinessException;

class GraphWhatsAppCloudClientTest {

    @Test
    void sendDocument_whenDisabled_doesNotCallGraph() {
        WhatsAppProperties properties = new WhatsAppProperties();
        properties.setEnabled(false);
        properties.setAccessToken("must-never-leave-this-class");
        GraphWhatsAppCloudClient client = new GraphWhatsAppCloudClient(properties);

        assertThatThrownBy(() -> client.sendDocument("250788123456", "%PDF".getBytes(), "r.pdf", "caption"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("not configured");
    }

    @Test
    void sendText_whenDisabled_doesNotCallGraph() {
        WhatsAppProperties properties = new WhatsAppProperties();
        properties.setEnabled(false);
        properties.setAccessToken("must-never-leave-this-class");
        GraphWhatsAppCloudClient client = new GraphWhatsAppCloudClient(properties);

        assertThatThrownBy(() -> client.sendText("250788123456", "hello"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("not configured");
    }
}
