package rw.terimbere.csams.modules.memberpayment.account;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import rw.terimbere.csams.modules.memberpayment.entity.PaymentMethod;
import rw.terimbere.csams.security.CooperativeAuthorizationService;
import rw.terimbere.csams.security.UserPrincipal;
import rw.terimbere.csams.modules.audit.service.AuditService;
import rw.terimbere.csams.shared.auditing.AuditableAction;
import rw.terimbere.csams.shared.exceptions.BusinessException;
import rw.terimbere.csams.shared.exceptions.ConflictException;
import rw.terimbere.csams.shared.exceptions.ForbiddenException;
import rw.terimbere.csams.shared.exceptions.ResourceNotFoundException;

/**
 * The accounts a Saving Scheme asks its members to pay into, and the comparison of a receipt's recipient with them.
 *
 * <p>Members see a masked identifier (enough to recognise the account, not enough to misuse it); officers who manage the
 * scheme's settings see and edit the full value. The comparison is for a person's benefit: it can raise a warning or
 * ask for a review, and it never verifies or posts a payment.
 */
@Service
@RequiredArgsConstructor
public class PaymentAccountService {

    private static final String SETTINGS_MANAGE = "SETTINGS_MANAGE";
    private static final String COOPERATIVE_WRITE = "COOPERATIVE_WRITE";

    private final SchemePaymentAccountRepository repository;
    private final CooperativeAuthorizationService authorizationService;
    private final AuditService auditService;
    private final org.springframework.jdbc.core.JdbcTemplate jdbc;

    public record AccountRequest(PaymentMethod channel, String accountIdentifier, String label) {}

    public record AccountResponse(
            UUID id,
            PaymentMethod channel,
            String accountIdentifier,
            boolean masked,
            String label,
            boolean active,
            Instant createdAt,
            /** True when nothing was ever submitted or read while this account was registered: it may be deleted. */
            boolean canDelete,
            /** PAYMENT_ACCOUNT_IN_USE when payments or receipts were handled while it was registered (then deactivate it). */
            String deletionBlockedReason) {}

    @Transactional(readOnly = true)
    public List<AccountResponse> list(UUID cooperativeId) {
        authorizationService.requireMembership(cooperativeId);
        boolean manager = canManage(authorizationService.currentPrincipal());
        return repository.findByCooperativeIdOrderByCreatedAtAsc(cooperativeId).stream()
                .filter(a -> manager || a.isActive())
                .map(a -> toResponse(a, !manager))
                .toList();
    }

    @Transactional
    public AccountResponse add(UUID cooperativeId, AccountRequest request) {
        authorizationService.requireMembership(cooperativeId);
        UserPrincipal actor = authorizationService.currentPrincipal();
        requireManager(actor);
        if (request == null || request.channel() == null || request.channel() == PaymentMethod.CASH) {
            throw new BusinessException("PAYMENT_ACCOUNT_CHANNEL_INVALID", "Choose a mobile-money, bank or other channel");
        }
        String identifier = normalize(request.accountIdentifier());
        if (identifier.length() < 6 || identifier.length() > 64) {
            throw new BusinessException("PAYMENT_ACCOUNT_INVALID", "The account number must have 6 to 64 letters or digits");
        }
        SchemePaymentAccount existing = repository
                .findByCooperativeIdAndChannelAndAccountIdentifier(cooperativeId, request.channel(), identifier)
                .orElse(null);
        if (existing != null) {
            if (existing.isActive()) {
                throw new ConflictException("PAYMENT_ACCOUNT_EXISTS", "This account is already registered for the scheme");
            }
            existing.setActive(true);
            existing.setAccountLabel(trim(request.label()));
            return toResponse(repository.save(existing), false);
        }
        SchemePaymentAccount saved;
        try {
            saved = repository.saveAndFlush(SchemePaymentAccount.builder()
                    .cooperativeId(cooperativeId)
                    .channel(request.channel())
                    .accountIdentifier(identifier)
                    .accountLabel(trim(request.label()))
                    .createdBy(actor.getId())
                    .build());
        } catch (DataIntegrityViolationException race) {
            throw new ConflictException("PAYMENT_ACCOUNT_EXISTS", "This account is already registered for the scheme");
        }
        auditService.record(
                actor.getId(), cooperativeId, AuditableAction.PAYMENT_ACCOUNT_ADDED, "SchemePaymentAccount", saved.getId(),
                null, "{\"channel\":\"" + saved.getChannel() + "\"}", null, null);
        return toResponse(saved, false);
    }

    @Transactional
    public void deactivate(UUID cooperativeId, UUID accountId) {
        authorizationService.requireMembership(cooperativeId);
        UserPrincipal actor = authorizationService.currentPrincipal();
        requireManager(actor);
        SchemePaymentAccount account = repository
                .findByIdAndCooperativeId(accountId, cooperativeId)
                .orElseThrow(() -> new ResourceNotFoundException("Payment account", accountId));
        if (account.isActive()) {
            account.setActive(false);
            repository.save(account);
            auditService.record(
                    actor.getId(), cooperativeId, AuditableAction.PAYMENT_ACCOUNT_DEACTIVATED, "SchemePaymentAccount",
                    accountId, "{\"active\":true}", "{\"active\":false}", null, null);
        }
    }

    /** Compares the recipient a receipt names with the scheme's active accounts. */
    @Transactional(readOnly = true)
    public RecipientMatch match(UUID cooperativeId, String recipient) {
        if (!StringUtils.hasText(recipient)) {
            return RecipientMatch.UNKNOWN;
        }
        List<SchemePaymentAccount> accounts = repository.findByCooperativeIdAndActiveTrue(cooperativeId);
        if (accounts.isEmpty()) {
            return RecipientMatch.UNKNOWN;
        }
        String named = normalize(recipient);
        return accounts.stream().anyMatch(a -> same(a.getAccountIdentifier(), named))
                ? RecipientMatch.MATCH
                : RecipientMatch.MISMATCH;
    }

    // ------------------------------------------------------------------ rules

    /** Upper-case letters and digits only: "+250 788 123 456" and "0788-123-456" compare on their digits. */
    public static String normalize(String raw) {
        return raw == null ? "" : raw.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "");
    }

    /**
     * Two normalised identifiers are the same account when they are equal, or, for phone numbers, when their last nine
     * digits are (so a national and an international spelling of one number agree).
     */
    static boolean same(String a, String b) {
        if (a.equals(b)) {
            return true;
        }
        if (a.matches("\\d{9,}") && b.matches("\\d{9,}")) {
            return a.substring(a.length() - 9).equals(b.substring(b.length() - 9));
        }
        return false;
    }

    /** Enough of an identifier to recognise it: everything but the last three characters is hidden. */
    public static String mask(String identifier) {
        if (identifier == null) {
            return null;
        }
        if (identifier.length() <= 3) {
            return "***";
        }
        return "*".repeat(identifier.length() - 3) + identifier.substring(identifier.length() - 3);
    }

    private AccountResponse toResponse(SchemePaymentAccount a, boolean masked) {
        // members never see removal rules; the answer is only worked out for those who manage the accounts
        boolean inUse = !masked && inUse(a);
        return new AccountResponse(
                a.getId(), a.getChannel(), masked ? mask(a.getAccountIdentifier()) : a.getAccountIdentifier(), masked,
                a.getAccountLabel(), a.isActive(), a.getCreatedAt(), !masked && !inUse, inUse ? "PAYMENT_ACCOUNT_IN_USE" : null);
    }

    /**
     * An account is in use when a payment through its channel was submitted, or a receipt was analysed, while it was
     * registered: those payments and receipts were checked against it, so it is part of how they can be explained later.
     */
    private boolean inUse(SchemePaymentAccount a) {
        java.sql.Timestamp since = java.sql.Timestamp.from(a.getCreatedAt() == null ? Instant.EPOCH : a.getCreatedAt());
        Long payments = jdbc.queryForObject(
                "select count(*) from member_payments where cooperative_id = ? and payment_method = ? and created_at >= ?",
                Long.class, a.getCooperativeId(), a.getChannel().name(), since);
        Long receipts = jdbc.queryForObject(
                "select count(*) from receipt_analyses where cooperative_id = ? and created_at >= ?",
                Long.class, a.getCooperativeId(), since);
        return (payments != null && payments > 0) || (receipts != null && receipts > 0);
    }

    /** Permanently removes an account that was never used. One that was used can only be deactivated. */
    @Transactional
    public void delete(UUID cooperativeId, UUID accountId) {
        authorizationService.requireMembership(cooperativeId);
        UserPrincipal actor = authorizationService.currentPrincipal();
        requireManager(actor);
        SchemePaymentAccount account = repository
                .findByIdAndCooperativeId(accountId, cooperativeId)
                .orElseThrow(() -> new ResourceNotFoundException("Payment account", accountId));
        if (inUse(account)) {
            throw new ConflictException(
                    "PAYMENT_ACCOUNT_IN_USE",
                    "Payments or receipts were handled while this account was registered. Deactivate it instead so its history stays explainable.");
        }
        auditService.record(
                actor.getId(), cooperativeId, AuditableAction.PAYMENT_ACCOUNT_DELETED, "SchemePaymentAccount", accountId,
                "{\"channel\":\"" + account.getChannel() + "\",\"active\":" + account.isActive() + "}", null, null, null);
        repository.delete(account);
    }

    private static boolean canManage(UserPrincipal principal) {
        return principal.hasRole(CooperativeAuthorizationService.SUPER_ADMIN)
                || principal.hasAuthority(SETTINGS_MANAGE)
                || principal.hasAuthority(COOPERATIVE_WRITE);
    }

    private static void requireManager(UserPrincipal principal) {
        if (!canManage(principal)) {
            throw new ForbiddenException("You do not have permission to manage the scheme's payment accounts");
        }
    }

    private static String trim(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        String t = value.trim();
        return t.length() > 100 ? t.substring(0, 100) : t;
    }
}
