package rw.terimbere.csams.modules.memberpayment.verification;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;
import rw.terimbere.csams.modules.audit.service.AuditService;
import rw.terimbere.csams.modules.cooperative.repository.CooperativeRepository;
import rw.terimbere.csams.modules.memberpayment.entity.PaymentMethod;
import rw.terimbere.csams.modules.memberpayment.service.PaymentReferenceNormalizer;
import rw.terimbere.csams.security.CooperativeAuthorizationService;
import rw.terimbere.csams.security.UserPrincipal;
import rw.terimbere.csams.shared.auditing.AuditableAction;
import rw.terimbere.csams.shared.exceptions.BusinessException;
import rw.terimbere.csams.shared.exceptions.ForbiddenException;
import rw.terimbere.csams.shared.exceptions.ResourceNotFoundException;

/**
 * Records trusted transactions: statement lines imported by an identified officer and provider callbacks.
 *
 * <p>Recording is idempotent (a repeated line or callback is a duplicate, not a second transaction) and
 * non-destructive (a record that disagrees with the stored one marks it conflicted and changes nothing else). A
 * pending record may later settle to success or failure. Recording matches payments and appends their outcome, but
 * never verifies or posts anything.
 */
@Service
@RequiredArgsConstructor
public class TrustedTransactionService {

    private static final String CONTRIBUTION_WRITE = "CONTRIBUTION_WRITE";
    private static final String LOAN_WRITE = "LOAN_WRITE";
    public static final int MAX_BATCH = 500;
    private static final int LIST_LIMIT = 200;

    public enum Result {
        CREATED,
        UPDATED,
        DUPLICATE,
        CONFLICT,
        INVALID
    }

    private final TrustedTransactionRepository repository;
    private final PaymentVerificationCheckService checkService;
    private final CooperativeRepository cooperativeRepository;
    private final CooperativeAuthorizationService authorizationService;
    private final AuditService auditService;
    private final TransactionTemplate transactionTemplate;

    /** What is being recorded, before validation. */
    public record Candidate(
            VerificationSource source,
            PaymentMethod channel,
            String reference,
            BigDecimal amount,
            String currency,
            ProviderStatus status,
            Instant transactedAt,
            String sender,
            String recipient,
            String eventId,
            UUID batchId,
            UUID recordedBy) {}

    public record Outcome(String reference, Result result, String reason, UUID transactionId) {}

    // ------------------------------------------------------------------ officer import

    public ImportResponse importBatch(UUID cooperativeId, ImportRequest request) {
        authorizationService.requireMembership(cooperativeId);
        UserPrincipal actor = authorizationService.currentPrincipal();
        if (!actor.hasRole(CooperativeAuthorizationService.SUPER_ADMIN)
                && !actor.hasAuthority(CONTRIBUTION_WRITE)
                && !actor.hasAuthority(LOAN_WRITE)) {
            throw new ForbiddenException("You do not have permission to import transactions");
        }
        if (request == null || request.items() == null || request.items().isEmpty()) {
            throw new BusinessException("IMPORT_EMPTY", "There are no transactions to import");
        }
        if (request.items().size() > MAX_BATCH) {
            throw new BusinessException("IMPORT_TOO_LARGE", "At most " + MAX_BATCH + " transactions per import");
        }
        UUID batchId = UUID.randomUUID();
        List<Outcome> outcomes = new ArrayList<>();
        for (ImportItem item : request.items()) {
            try {
                outcomes.add(record(
                        cooperativeId,
                        new Candidate(
                                item.source(),
                                item.channel(),
                                item.reference(),
                                item.amount(),
                                item.currency(),
                                item.status() == null ? ProviderStatus.SUCCESS : item.status(),
                                item.transactedAt(),
                                item.sender(),
                                item.recipient(),
                                null,
                                batchId,
                                actor.getId()),
                        actor.getId()));
            } catch (BusinessException e) {
                outcomes.add(new Outcome(item.reference(), Result.INVALID, e.getErrorCode(), null));
            }
        }
        return new ImportResponse(batchId, outcomes);
    }

    @org.springframework.transaction.annotation.Transactional(readOnly = true)
    public List<TransactionResponse> list(UUID cooperativeId) {
        authorizationService.requireMembership(cooperativeId);
        UserPrincipal actor = authorizationService.currentPrincipal();
        if (!actor.hasRole(CooperativeAuthorizationService.SUPER_ADMIN)
                && !actor.hasAuthority(CONTRIBUTION_WRITE)
                && !actor.hasAuthority(LOAN_WRITE)) {
            throw new ForbiddenException("You do not have permission to view trusted transactions");
        }
        return repository.findByCooperativeIdOrderByRecordedAtDesc(cooperativeId, PageRequest.of(0, LIST_LIMIT)).stream()
                .map(TransactionResponse::from)
                .toList();
    }

    // ------------------------------------------------------------------ provider callback

    /** Recorded by the system from a signed callback; there is no acting person. */
    public Outcome recordCallback(UUID cooperativeId, Candidate candidate) {
        if (!cooperativeRepository.existsById(cooperativeId)) {
            throw new ResourceNotFoundException("Cooperative", cooperativeId);
        }
        return record(cooperativeId, candidate, null);
    }

    // ------------------------------------------------------------------ core

    /**
     * Records the statement line a member payment was reconciled with, as imported by the officer who uploaded the
     * statement. Mobile money paid into the scheme's bank account appears on the bank statement, so a bank statement line
     * may stand for a mobile-money payment here.
     */
    public Outcome recordFromStatement(UUID cooperativeId, VerificationSource source, PaymentMethod channel, String reference,
            BigDecimal amount, String currency, Instant transactedAt, UUID uploadedBy) {
        return record(cooperativeId, new Candidate(source, channel, reference, amount, currency, ProviderStatus.SUCCESS, transactedAt,
                null, null, null, null, uploadedBy), uploadedBy, true);
    }

    Outcome record(UUID cooperativeId, Candidate c, UUID actorId) {
        return record(cooperativeId, c, actorId, false);
    }

    private Outcome record(UUID cooperativeId, Candidate c, UUID actorId, boolean statementOfPayment) {
        validate(c, statementOfPayment);
        String normalized = PaymentReferenceNormalizer.normalize(c.reference());
        Outcome outcome;
        try {
            outcome = transactionTemplate.execute(status -> recordOnce(cooperativeId, c, normalized, actorId));
        } catch (DataIntegrityViolationException race) {
            // a concurrent request recorded the same transaction or event first: classify against what it stored
            outcome = transactionTemplate.execute(status -> recordOnce(cooperativeId, c, normalized, actorId));
        }
        if (outcome.result() == Result.CREATED || outcome.result() == Result.UPDATED || outcome.result() == Result.CONFLICT) {
            checkService.reevaluateReference(cooperativeId, c.channel(), normalized);
        }
        return outcome;
    }

    private Outcome recordOnce(UUID cooperativeId, Candidate c, String normalized, UUID actorId) {
        if (StringUtils.hasText(c.eventId())) {
            TrustedTransaction byEvent = repository
                    .findByCooperativeIdAndSourceAndEventId(cooperativeId, c.source(), c.eventId())
                    .orElse(null);
            if (byEvent != null) {
                return new Outcome(c.reference(), Result.DUPLICATE, "EVENT_ALREADY_RECORDED", byEvent.getId());
            }
        }
        TrustedTransaction existing = repository.findForUpdate(cooperativeId, c.channel(), normalized).orElse(null);
        if (existing == null) {
            TrustedTransaction saved = repository.saveAndFlush(TrustedTransaction.builder()
                    .cooperativeId(cooperativeId)
                    .source(c.source())
                    .channel(c.channel())
                    .transactionReference(c.reference().trim())
                    .normalizedReference(normalized)
                    .amount(c.amount())
                    .currency(c.currency().toUpperCase(Locale.ROOT))
                    .transactedAt(c.transactedAt())
                    .sender(trim(c.sender(), 64))
                    .recipient(trim(c.recipient(), 64))
                    .providerStatus(c.status())
                    .eventId(StringUtils.hasText(c.eventId()) ? c.eventId() : null)
                    .batchId(c.batchId())
                    .recordedBy(actorId)
                    .recordedAt(Instant.now())
                    .build());
            audit(actorId, cooperativeId, saved, "CREATED");
            return new Outcome(c.reference(), Result.CREATED, null, saved.getId());
        }
        boolean sameFacts = existing.getAmount().compareTo(c.amount()) == 0
                && existing.getCurrency().equalsIgnoreCase(c.currency());
        if (sameFacts && existing.getProviderStatus() == c.status()) {
            return new Outcome(c.reference(), Result.DUPLICATE, "ALREADY_RECORDED", existing.getId());
        }
        if (sameFacts && existing.getProviderStatus() == ProviderStatus.PENDING) {
            existing.setProviderStatus(c.status());
            repository.saveAndFlush(existing);
            audit(actorId, cooperativeId, existing, "SETTLED_" + c.status());
            return new Outcome(c.reference(), Result.UPDATED, null, existing.getId());
        }
        if (sameFacts && c.status() == ProviderStatus.PENDING) {
            // a stale "still pending" report after the transaction settled: nothing new
            return new Outcome(c.reference(), Result.DUPLICATE, "ALREADY_SETTLED", existing.getId());
        }
        if (!existing.isConflicted()) {
            existing.setConflictedAt(Instant.now());
            existing.setConflictDetail(trim(
                    "A later record disagrees: " + (sameFacts ? "status " + c.status() : "amount/currency"), 500));
            repository.saveAndFlush(existing);
        }
        audit(actorId, cooperativeId, existing, "CONFLICT");
        return new Outcome(c.reference(), Result.CONFLICT, "CONFLICTING_RECORD", existing.getId());
    }

    private static void validate(Candidate c, boolean statementOfPayment) {
        if (c.source() == null || !c.source().recordsTransactions()) {
            throw new BusinessException("INVALID_TRUSTED_SOURCE", "The source of a trusted transaction is required");
        }
        if (c.channel() == null || c.channel() == PaymentMethod.CASH) {
            throw new BusinessException("INVALID_CHANNEL", "A trusted transaction needs an electronic channel");
        }
        boolean fits = switch (c.source()) {
            case BANK_STATEMENT_IMPORT -> c.channel() == PaymentMethod.BANK_TRANSFER || c.channel() == PaymentMethod.OTHER
                    || (statementOfPayment && (c.channel() == PaymentMethod.MTN_MOMO || c.channel() == PaymentMethod.AIRTEL_MONEY));
            case MOBILE_MONEY_STATEMENT_IMPORT -> c.channel() == PaymentMethod.MTN_MOMO
                    || c.channel() == PaymentMethod.AIRTEL_MONEY
                    || c.channel() == PaymentMethod.OTHER;
            default -> true;
        };
        if (!fits) {
            throw new BusinessException("SOURCE_CHANNEL_MISMATCH", c.source() + " cannot record a " + c.channel() + " transaction");
        }
        if (!StringUtils.hasText(c.reference())) {
            throw new BusinessException("INVALID_PAYMENT_REFERENCE", "A transaction reference is required");
        }
        if (c.amount() == null || c.amount().signum() <= 0 || c.amount().scale() > 4) {
            throw new BusinessException("INVALID_AMOUNT", "Amount must be positive with at most 4 decimals");
        }
        if (c.currency() == null || !c.currency().matches("[A-Za-z]{3}")) {
            throw new BusinessException("INVALID_CURRENCY", "Currency must be a 3-letter code");
        }
        if (c.status() == null) {
            throw new BusinessException("INVALID_STATUS", "A provider status is required");
        }
        if (c.source() == VerificationSource.PROVIDER_CALLBACK && !StringUtils.hasText(c.eventId())) {
            throw new BusinessException("EVENT_ID_REQUIRED", "A callback needs an event id");
        }
        if (c.source() != VerificationSource.PROVIDER_CALLBACK && c.recordedBy() == null) {
            throw new BusinessException("RECORDER_REQUIRED", "An imported transaction is recorded by an identified officer");
        }
    }

    private void audit(UUID actorId, UUID cooperativeId, TrustedTransaction tx, String what) {
        auditService.record(
                actorId,
                cooperativeId,
                AuditableAction.TRUSTED_TRANSACTION_RECORDED,
                "TrustedTransaction",
                tx.getId(),
                null,
                "{\"result\":\"" + what + "\",\"source\":\"" + tx.getSource() + "\",\"channel\":\"" + tx.getChannel() + "\"}",
                null,
                null);
    }

    private static String trim(String value, int max) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        String t = value.trim();
        return t.length() > max ? t.substring(0, max) : t;
    }

    // ------------------------------------------------------------------ DTOs

    public record ImportItem(
            VerificationSource source,
            PaymentMethod channel,
            String reference,
            BigDecimal amount,
            String currency,
            ProviderStatus status,
            Instant transactedAt,
            String sender,
            String recipient) {}

    public record ImportRequest(List<ImportItem> items) {}

    public record ImportResponse(UUID batchId, List<Outcome> results) {}

    public record TransactionResponse(
            UUID id,
            VerificationSource source,
            PaymentMethod channel,
            String reference,
            BigDecimal amount,
            String currency,
            ProviderStatus providerStatus,
            Instant transactedAt,
            String sender,
            String recipient,
            boolean consumed,
            UUID consumedByPaymentId,
            boolean conflicted,
            String conflictDetail,
            Instant recordedAt) {
        static TransactionResponse from(TrustedTransaction t) {
            return new TransactionResponse(
                    t.getId(),
                    t.getSource(),
                    t.getChannel(),
                    t.getTransactionReference(),
                    t.getAmount(),
                    t.getCurrency(),
                    t.getProviderStatus(),
                    t.getTransactedAt(),
                    t.getSender(),
                    t.getRecipient(),
                    t.getConsumedByPaymentId() != null,
                    t.getConsumedByPaymentId(),
                    t.isConflicted(),
                    t.getConflictDetail(),
                    t.getRecordedAt());
        }
    }
}
