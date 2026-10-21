package rw.terimbere.csams.modules.notification;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import rw.terimbere.csams.modules.notification.channel.InAppNotificationPublisher;
import rw.terimbere.csams.modules.notification.channel.NotificationChannel;
import rw.terimbere.csams.modules.notification.entity.NotificationType;
import rw.terimbere.csams.modules.notification.service.NotificationFacade;

class NotificationFacadeTest {

    @Test
    void notifyUser_invokesAllChannelsIncludingInApp() {
        NotificationChannel inApp = mock(InAppNotificationPublisher.class);
        NotificationChannel email = mock(NotificationChannel.class);
        NotificationFacade facade = new NotificationFacade(List.of(inApp, email));

        UUID userId = UUID.randomUUID();
        UUID coopId = UUID.randomUUID();
        UUID entityId = UUID.randomUUID();

        facade.notifyUser(
                userId, coopId, NotificationType.LOAN, "Loan disbursed", "body", "Loan", entityId);

        verify(inApp)
                .publish(
                        eq(userId),
                        eq(coopId),
                        eq(NotificationType.LOAN),
                        eq("Loan disbursed"),
                        eq("body"),
                        eq("Loan"),
                        eq(entityId));
        verify(email)
                .publish(
                        eq(userId),
                        eq(coopId),
                        eq(NotificationType.LOAN),
                        eq("Loan disbursed"),
                        eq("body"),
                        eq("Loan"),
                        eq(entityId));
    }

    @Test
    void notifyUser_skipsNullUser() {
        NotificationChannel inApp = mock(NotificationChannel.class);
        NotificationFacade facade = new NotificationFacade(List.of(inApp));
        facade.notifyUser(null, null, NotificationType.SYSTEM, "t", "b", null, null);
        org.mockito.Mockito.verifyNoInteractions(inApp);
    }
}
