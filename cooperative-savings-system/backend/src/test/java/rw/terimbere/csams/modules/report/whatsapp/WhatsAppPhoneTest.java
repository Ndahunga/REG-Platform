package rw.terimbere.csams.modules.report.whatsapp;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class WhatsAppPhoneTest {

    @Test
    void convertsRwandanLocalAndInternationalForms() {
        assertThat(WhatsAppPhone.toRecipient("0788123456")).isEqualTo("250788123456");
        assertThat(WhatsAppPhone.toRecipient("+250 788 123 456")).isEqualTo("250788123456");
        assertThat(WhatsAppPhone.toRecipient("250788123456")).isEqualTo("250788123456");
        assertThat(WhatsAppPhone.toRecipient("788123456")).isEqualTo("250788123456");
    }

    @Test
    void rejectsInvalidNumbers() {
        assertThat(WhatsAppPhone.toRecipient(null)).isNull();
        assertThat(WhatsAppPhone.toRecipient("")).isNull();
        assertThat(WhatsAppPhone.toRecipient("12345")).isNull();
        assertThat(WhatsAppPhone.toRecipient("0015551234")).isNull();
    }
}
