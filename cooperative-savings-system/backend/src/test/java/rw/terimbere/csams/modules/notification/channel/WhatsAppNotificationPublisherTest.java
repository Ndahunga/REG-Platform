package rw.terimbere.csams.modules.notification.channel;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import rw.terimbere.csams.modules.notification.entity.NotificationType;
import rw.terimbere.csams.modules.notification.service.NotificationWhatsAppService;

@ExtendWith(MockitoExtension.class)
class WhatsAppNotificationPublisherTest {

    @Mock
    private NotificationWhatsAppService notificationWhatsAppService;

    @InjectMocks
    private WhatsAppNotificationPublisher publisher;

    @Test
    void publish_delegatesAndSwallowsFailures() {
        UUID userId = UUID.randomUUID();
        UUID coopId = UUID.randomUUID();
        UUID entityId = UUID.randomUUID();
        publisher.publish(
                userId, coopId, NotificationType.CONTRIBUTION, "Contribution approved", "body", "Contribution", entityId);
        verify(notificationWhatsAppService)
                .deliverFromInApp(
                        userId, coopId, NotificationType.CONTRIBUTION, "Contribution approved", "body", "Contribution", entityId);

        doThrow(new RuntimeException("boom"))
                .when(notificationWhatsAppService)
                .deliverFromInApp(any(), any(), any(), any(), any(), any(), any());
        assertThatCode(() -> publisher.publish(
                        userId, coopId, NotificationType.LOAN, "Loan rejected", "body", "Loan", entityId))
                .doesNotThrowAnyException();
    }
}
