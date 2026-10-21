package rw.terimbere.csams.modules.notification.service;

import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.StringUtils;
import rw.terimbere.csams.modules.membership.entity.CooperativeMembership;
import rw.terimbere.csams.modules.membership.repository.CooperativeMembershipRepository;
import rw.terimbere.csams.modules.notification.entity.NotificationType;
import rw.terimbere.csams.modules.notification.whatsapp.NotificationWhatsAppCopy;
import rw.terimbere.csams.modules.report.whatsapp.WhatsAppCloudClient;
import rw.terimbere.csams.modules.report.whatsapp.WhatsAppPhone;
import rw.terimbere.csams.modules.report.whatsapp.WhatsAppProperties;
import rw.terimbere.csams.modules.user.entity.User;
import rw.terimbere.csams.modules.user.repository.UserRepository;
import rw.terimbere.csams.security.CooperativeAccessResolver;

/**
 * Optional WhatsApp delivery for existing in-app notification events.
 * Failures never propagate to contribution/loan transactions.
 */
@Service
public class NotificationWhatsAppService {

    private static final Logger log = LoggerFactory.getLogger(NotificationWhatsAppService.class);
    private static final int MAX_BODY = 4000;

    private final WhatsAppCloudClient whatsAppCloudClient;
    private final WhatsAppProperties whatsAppProperties;
    private final UserRepository userRepository;
    private final CooperativeMembershipRepository membershipRepository;
    private final CooperativeAccessResolver accessResolver;
    private final Executor taskExecutor;

    public NotificationWhatsAppService(
            WhatsAppCloudClient whatsAppCloudClient,
            WhatsAppProperties whatsAppProperties,
            UserRepository userRepository,
            CooperativeMembershipRepository membershipRepository,
            CooperativeAccessResolver accessResolver,
            @Qualifier("taskExecutor") Executor taskExecutor) {
        this.whatsAppCloudClient = whatsAppCloudClient;
        this.whatsAppProperties = whatsAppProperties;
        this.userRepository = userRepository;
        this.membershipRepository = membershipRepository;
        this.accessResolver = accessResolver;
        this.taskExecutor = taskExecutor;
    }

    public void deliverFromInApp(
            UUID userId,
            UUID cooperativeId,
            NotificationType type,
            String title,
            String body,
            String entityType,
            UUID entityId) {
        if (userId == null || !whatsAppProperties.isConfigured()) {
            return;
        }
        String message = NotificationWhatsAppCopy.fromInApp(title, body);
        if (!StringUtils.hasText(message)) {
            return;
        }
        runAfterCommit(() -> sendToUser(userId, message));
    }

    public void notifyOfficers(UUID cooperativeId, String permissionCode, String message, UUID excludeUserId) {
        if (cooperativeId == null || !StringUtils.hasText(permissionCode) || !whatsAppProperties.isConfigured()) {
            return;
        }
        if (!StringUtils.hasText(message)) {
            return;
        }
        runAfterCommit(() -> {
            try {
                for (UUID officerId : officerRecipientIds(cooperativeId, permissionCode)) {
                    if (officerId.equals(excludeUserId)) {
                        continue;
                    }
                    sendToUser(officerId, message);
                }
            } catch (Exception ex) {
                log.warn(
                        "WhatsApp officer notify skipped: {}",
                        ex.getClass().getSimpleName());
            }
        });
    }

    /**
     * Users who currently hold {@code permissionCode} <em>in this cooperative</em>: ACTIVE members whose
     * {@code role_in_cooperative} maps to that permission. Global user roles are deliberately not used, so an
     * accountant in cooperative A who is only a member of cooperative B is never treated as an officer of B.
     * Active members who are platform Super Admins keep receiving officer notifications.
     */
    public Set<UUID> officerRecipientIds(UUID cooperativeId, String permissionCode) {
        List<CooperativeMembership> activeMembers = membershipRepository.findActiveByCooperativeId(cooperativeId);
        Set<UUID> recipients = new LinkedHashSet<>();
        Set<UUID> activeUserIds = new HashSet<>();
        for (CooperativeMembership membership : activeMembers) {
            if (membership.getUserId() == null) {
                continue;
            }
            activeUserIds.add(membership.getUserId());
            if (accessResolver
                    .permissionsForMembershipRole(membership.getRoleInCooperative())
                    .contains(permissionCode)) {
                recipients.add(membership.getUserId());
            }
        }
        if (!activeUserIds.isEmpty()) {
            recipients.addAll(userRepository.findSuperAdminIdsAmong(activeUserIds));
        }
        return recipients;
    }

    private void sendToUser(UUID userId, String message) {
        try {
            User user = userRepository.findByIdAndDeletedFalse(userId).orElse(null);
            if (user == null) {
                return;
            }
            String recipient = WhatsAppPhone.toRecipient(user.getPhone());
            if (recipient == null) {
                log.debug("Skipping WhatsApp notification: no valid recipient for user {}", userId);
                return;
            }
            String truncated = message.length() <= MAX_BODY ? message : message.substring(0, MAX_BODY);
            whatsAppCloudClient.sendText(recipient, truncated);
        } catch (Exception ex) {
            log.warn(
                    "WhatsApp notification failed for user {}: {}",
                    userId,
                    ex.getClass().getSimpleName());
        }
    }

    private void runAfterCommit(Runnable task) {
        if (TransactionSynchronizationManager.isSynchronizationActive()
                && TransactionSynchronizationManager.isActualTransactionActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    taskExecutor.execute(task);
                }
            });
            return;
        }
        taskExecutor.execute(task);
    }
}
