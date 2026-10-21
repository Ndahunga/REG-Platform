package rw.terimbere.csams.modules.notification.mail;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class NotificationEmailCopyTest {

    @Test
    void subjectAndBodiesUseTitleAndBrandWithoutTechnicalIds() throws Exception {
        String title = "Loan approved";
        String body = "Your loan request was approved by Jane Doe.";

        assertThat(NotificationEmailCopy.subject(title)).isEqualTo(title);
        assertThat(NotificationEmailCopy.plainText(title, body))
                .startsWith("OuWealth Community")
                .contains(body)
                .contains("Please do not reply to this email.")
                .doesNotContain("<script>");
        assertThat(NotificationEmailCopy.html(title, body))
                .contains("OuWealth Community")
                .contains(body)
                .contains("Please do not reply to this email.");
        assertThat(NotificationEmailCopy.html("<script>alert(1)</script>", "<script>alert(1)</script>"))
                .contains("&lt;script&gt;")
                .doesNotContain("<script>alert(1)</script>");
        assertThat(NotificationEmailCopy.fromAddress("noreply@example.test").toString())
                .contains("OuWealth Community")
                .contains("noreply@example.test");
    }
}
