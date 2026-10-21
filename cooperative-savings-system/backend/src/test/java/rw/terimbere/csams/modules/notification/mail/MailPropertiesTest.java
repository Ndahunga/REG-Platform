package rw.terimbere.csams.modules.notification.mail;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class MailPropertiesTest {

    @Test
    void configuredOnlyWhenEnabledHostAndFromArePresent() {
        MailProperties properties = new MailProperties();
        assertThat(properties.isConfigured()).isFalse();

        properties.setHost("smtp.example.com");
        assertThat(properties.isConfigured()).isFalse();

        properties.setFrom("noreply@example.com");
        assertThat(properties.isConfigured()).isTrue();

        properties.setEnabled(false);
        assertThat(properties.isConfigured()).isFalse();
    }
}
