package rw.terimbere.csams.modules.memberpayment.verification;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;
import rw.terimbere.csams.modules.audit.service.AuditService;
import rw.terimbere.csams.modules.memberpayment.entity.MemberPayment;
import rw.terimbere.csams.modules.memberpayment.entity.PaymentMethod;
import rw.terimbere.csams.modules.memberpayment.entity.PaymentStatus;
import rw.terimbere.csams.modules.memberpayment.entity.VerificationMethod;
import rw.terimbere.csams.modules.memberpayment.repository.MemberPaymentRepository;
import rw.terimbere.csams.modules.memberpayment.verification.TransactionMatchingService.Decision;
import rw.terimbere.csams.security.CooperativeAuthorizationService;
import rw.terimbere.csams.security.UserPrincipal;
import rw.terimbere.csams.shared.auditing.AuditableAction;
import rw.terimbere.csams.shared.exceptions.BusinessException;
import rw.terimbere.csams.shared.exceptions.ForbiddenException;
import rw.terimbere.csams.shared.exceptions.ResourceNotFoundException;

/**
 * Records verification outcomes (an append-only history) and enforces them at the one place that matters: the moment
 * an officer verifies a payment.
 *
 * <p>Nothing here moves a payment through its lifecycle, and nothing here posts money. Automation only records what
 * the evidence says; an officer still verifies and a different officer still posts. Real provider polling is not
 * implemented, so {@code automaticProviderConfirmation} is reported unavailable unless a callback secret is configured.
 */
@Service
@RequiredArgsConstructor
public class PaymentVerificationCheckService {

    private static final String CONTRIBUTION_WRITE = "CONTRIBUTION_WRITE";
    private static final String LOAN_WRITE = "LOAN_WRITE";
    private static final int ATTENTION_LIMIT = 200;
    private static final EnumSet<PaymentStatus> CLOSED = EnumSet.of(PaymentStatus.REJECTED, PaymentStatus.REVERSED);

    private final PaymentVerificationCheckRepository checkRepository;
    private final TrustedTransactionRepository transactionRepository;
    private final MemberPaymentRepository paymentRepository;
    private final TransactionMatchingService matchingService;
    private final CooperativeAuthorizationService authorizationService;
    private final AuditService auditService;
    private final TransactionTemplate transactionTemplate;
    private final ProviderCallbackProperties callbackProperties;
    private final VerificationAuditService auditTrail;

    /**
     * When true (the default, and what production runs), verifying and posting a payment need a CONFIRMED check from
     * authoritative evidence, except for the legacy payments flagged at V39. It can be switched off only by explicit
     * configuration; a CONFLICTED or FAILED check blocks verification and posting either way.
     */
    @Value("${app.payments.verification.require-trusted-confirmation:true}")
    private boolean requireTrustedConfirmation;

    // ------------------------------------------------------------------ recording

    /** Re-evaluates the payment against the trusted evidence now and appends the outcome if it changed. */
    @Transactional
    public CheckResponse recheck(UUID cooperativeId, UUID paymentId) {
        UserPrincipal actor = requireStaff(cooperativeId);
        MemberPayment payment = lockPayment(cooperativeId, paymentId);
        PaymentVerificationCheck before = latest(payment).orElse(null);
        PaymentVerificationCheck current = evaluateAndRecordLocked(payment, null);
        if (before == null || !before.getId().equals(current.getId())) {
            audit(actor.getId(), current);
        }
        return CheckResponse.from(current);
    }

    /**
     * Records the outcome for a payment on behalf of the system (import, callback, receipt reading). Runs in its own
     * transaction with the payment row locked, so it serializes with verification.
     */
    public void evaluateSystem(UUID cooperativeId, UUID paymentId) {
        transactionTemplate.executeWithoutResult(status -> {
            MemberPayment payment = paymentRepository
                    .findByIdAndCooperativeIdForUpdate(paymentId, cooperativeId)
                    .orElse(null);
            if (payment == null || CLOSED.contains(payment.getStatus())) {
                return;
            }
            PaymentVerificationCheck before = latest(payment).orElse(null);
            PaymentVerificationCheck current = evaluateAndRecordLocked(payment, null);
            if (before == null || !before.getId().equals(current.getId())) {
                audit(null, current);
            }
        });
    }

    /** Re-evaluates every open payment that carries this channel and reference (after an import or a callback). */
    public void reevaluateReference(UUID cooperativeId, PaymentMethod channel, String normalizedReference) {
        for (MemberPayment p : paymentRepository.findByCooperativeIdAndPaymentMethodAndNormalizedReference(
                cooperativeId, channel, normalizedReference)) {
            if (!CLOSED.contains(p.getStatus())) {
                evaluateSystem(cooperativeId, p.getId());
            }
        }
    }

    /**
     * An authorized officer confirms they received the cash. This is the only way a cash payment gets a CONFIRMED
     * outcome. The payer and the person who submitted the payment cannot confirm it.
     */
    @Transactional
    public CheckResponse confirmCash(UUID cooperativeId, UUID paymentId, String note) {
        UserPrincipal actor = requireStaff(cooperativeId);
        MemberPayment payment = lockPayment(cooperativeId, paymentId);
        if (payment.getPaymentMethod() != PaymentMethod.CASH) {
            throw new BusinessException(
                    "CASH_CONFIRMATION_NOT_APPLICABLE", "Only a cash payment is confirmed by receiving the cash");
        }
        if (payment.getStatus() != PaymentStatus.SUBMITTED && payment.getStatus() != PaymentStatus.UNDER_REVIEW) {
            throw new BusinessException(
                    "PAYMENT_NOT_OPEN", "Cash can only be confirmed while the payment is awaiting review");
        }
        if (actor.getId().equals(payment.getMemberUserId()) || actor.getId().equals(payment.getSubmittedBy())) {
            throw new ForbiddenException("The payer and the person who submitted a payment cannot confirm its cash");
        }
        if (!StringUtils.hasText(note)) {
            throw new BusinessException("CASH_CONFIRMATION_NOTE_REQUIRED", "State how you received the cash");
        }
        PaymentVerificationCheck latest = latest(payment).orElse(null);
        if (latest != null
                && latest.getOutcome() == VerificationOutcome.CONFIRMED
                && latest.getSource() == VerificationSource.MANUAL_CASH_CONFIRMATION) {
            return CheckResponse.from(latest);
        }
        PaymentVerificationCheck saved = append(
                payment,
                VerificationOutcome.CONFIRMED,
                VerificationMethod.CASH_RECEIPT_CONFIRMED,
                VerificationSource.MANUAL_CASH_CONFIRMATION,
                null,
                List.of(),
                trim(note, 1000),
                actor.getId(),
                latest);
        audit(actor.getId(), saved, AuditableAction.CASH_RECEIPT_CONFIRMED);
        return CheckResponse.from(saved);
    }

    // ------------------------------------------------------------------ the gate

    /**
     * Called by the verify operation with the payment row already locked. Refuses a payment whose evidence is
     * conflicting or failed, requires authoritative confirmation when configured, separates the officer who recorded a
     * transaction from the one who verifies, and consumes the transaction so no other payment can use it.
     */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    public void enforceBeforeVerification(MemberPayment payment, UserPrincipal actor, VerificationMethod evidenceMethod) {
        PaymentVerificationCheck current = requireTrustedConfirmation
                ? evaluateAndRecordLocked(payment, null)
                : latest(payment).orElse(null);
        if (current != null
                && (current.getOutcome() == VerificationOutcome.CONFLICTED
                        || current.getOutcome() == VerificationOutcome.FAILED)) {
            throw refuse(actor, payment, "VERIFY", "VERIFICATION_BLOCKED_BY_CHECK",
                    "The trusted evidence for this payment is " + current.getOutcome()
                            + " (" + Objects.toString(current.getReasonCodes(), "") + "); it cannot be verified");
        }
        if (requireTrustedConfirmation) {
            if (current == null || current.getOutcome() != VerificationOutcome.CONFIRMED) {
                throw refuse(actor, payment, "VERIFY", "TRUSTED_CONFIRMATION_REQUIRED",
                        "This payment has no authoritative confirmation yet. Import the statement line, wait for the"
                                + " provider confirmation, or (for cash) record the cash confirmation.");
            }
            if (current.getMethod() != evidenceMethod) {
                throw refuse(actor, payment, "VERIFY", "VERIFICATION_METHOD_NOT_CONFIRMED",
                        "The confirmed evidence is " + current.getMethod() + ", not " + evidenceMethod);
            }
        }
        if (current == null
                || current.getOutcome() != VerificationOutcome.CONFIRMED
                || current.getTrustedTransactionId() == null) {
            return;
        }
        TrustedTransaction tx = transactionRepository
                .findByIdForUpdate(current.getTrustedTransactionId())
                .orElseThrow(() -> new ResourceNotFoundException("Trusted transaction", current.getTrustedTransactionId()));
        if (tx.getRecordedBy() != null && tx.getRecordedBy().equals(actor.getId())) {
            throw refuse(actor, payment, "VERIFY", "VERIFIER_RECORDED_TRANSACTION",
                    "The officer who recorded the trusted transaction cannot also verify the payment it confirms");
        }
        if (tx.getConsumedByPaymentId() != null && !tx.getConsumedByPaymentId().equals(payment.getId())) {
            throw refuse(actor, payment, "VERIFY", "TRANSACTION_ALREADY_USED",
                    "This transaction was already used to verify another payment");
        }
        if (tx.getConsumedByPaymentId() == null) {
            tx.setConsumedByPaymentId(payment.getId());
            tx.setConsumedAt(Instant.now());
            transactionRepository.saveAndFlush(tx);
        }
    }

    /**
     * Called by the posting operation with the payment row already locked. A conflicting or failed outcome blocks
     * posting, and unless the payment is a flagged legacy exception a CONFIRMED outcome is required, so money is never
     * posted for a payment whose receipt of funds was not credibly and independently confirmed.
     */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    public void enforceBeforePosting(MemberPayment payment, UserPrincipal actor) {
        PaymentVerificationCheck current = latest(payment).orElse(null);
        if (current != null
                && (current.getOutcome() == VerificationOutcome.CONFLICTED
                        || current.getOutcome() == VerificationOutcome.FAILED)) {
            throw refuse(actor, payment, "POST", "VERIFICATION_BLOCKED_BY_CHECK",
                    "The trusted evidence for this payment is " + current.getOutcome() + "; it cannot be posted");
        }
        if (requireTrustedConfirmation
                && !payment.isLegacyVerificationException()
                && (current == null || current.getOutcome() != VerificationOutcome.CONFIRMED)) {
            throw refuse(actor, payment, "POST", "TRUSTED_CONFIRMATION_REQUIRED",
                    "This payment has no authoritative confirmation, so it cannot be posted");
        }
    }

    /** True when this deployment requires authoritative confirmation (the secure default). */
    public boolean strictVerification() {
        return requireTrustedConfirmation;
    }

    /** Whether any provider callback secret is configured (never the secrets themselves). */
    public boolean providerCallbackConfigured() {
        return callbackProperties.anyConfigured();
    }

    /** Records the refusal independently of this request, then returns the exception to throw. */
    private BusinessException refuse(UserPrincipal actor, MemberPayment payment, String stage, String code, String message) {
        auditTrail.blocked(actor == null ? null : actor.getId(), payment.getCooperativeId(), payment.getId(), stage, code);
        return new BusinessException(code, message);
    }

    // ------------------------------------------------------------------ reads

    @Transactional(readOnly = true)
    public Overview overview(UUID cooperativeId, UUID paymentId) {
        requireStaff(cooperativeId);
        paymentRepository
                .findByIdAndCooperativeId(paymentId, cooperativeId)
                .orElseThrow(() -> new ResourceNotFoundException("Payment", paymentId));
        List<CheckResponse> history = checkRepository
                .findByCooperativeIdAndPaymentIdOrderByCreatedAtAsc(cooperativeId, paymentId)
                .stream()
                .map(CheckResponse::from)
                .toList();
        return new Overview(
                requireTrustedConfirmation,
                callbackProperties.anyConfigured() ? "CALLBACK_CONFIGURED" : "UNAVAILABLE",
                history.isEmpty() ? null : history.get(history.size() - 1),
                history);
    }

    /** Open payments whose current outcome needs an officer to look: everything that is not CONFIRMED. */
    @Transactional(readOnly = true)
    public List<AttentionItem> attention(UUID cooperativeId) {
        requireStaff(cooperativeId);
        List<PaymentVerificationCheck> current = checkRepository.currentWithOutcome(
                cooperativeId,
                EnumSet.of(
                        VerificationOutcome.CONFLICTED,
                        VerificationOutcome.FAILED,
                        VerificationOutcome.UNMATCHED,
                        VerificationOutcome.PENDING));
        Map<UUID, MemberPayment> payments = paymentRepository
                .findAllById(current.stream().map(PaymentVerificationCheck::getPaymentId).distinct().toList())
                .stream()
                .collect(Collectors.toMap(MemberPayment::getId, Function.identity()));
        List<AttentionItem> items = new ArrayList<>();
        for (PaymentVerificationCheck c : current) {
            MemberPayment p = payments.get(c.getPaymentId());
            if (p == null || CLOSED.contains(p.getStatus()) || p.getStatus() == PaymentStatus.POSTED) {
                continue;
            }
            items.add(new AttentionItem(
                    p.getId(),
                    p.getMemberUserId(),
                    p.getAmount(),
                    p.getCurrency(),
                    p.getPaymentMethod(),
                    p.getTransactionReference(),
                    p.getStatus(),
                    c.getOutcome(),
                    splitReasons(c.getReasonCodes()),
                    c.getCreatedAt()));
            if (items.size() >= ATTENTION_LIMIT) {
                break;
            }
        }
        return items;
    }

    // ------------------------------------------------------------------ internals

    private PaymentVerificationCheck evaluateAndRecordLocked(MemberPayment payment, UUID actorId) {
        PaymentVerificationCheck latest = latest(payment).orElse(null);
        if (payment.getPaymentMethod() == PaymentMethod.CASH && latest != null) {
            return latest; // cash is only ever changed by an officer's confirmation
        }
        Decision decision = matchingService.evaluate(payment);
        if (latest != null && sameOutcome(latest, decision)) {
            return latest;
        }
        return append(
                payment,
                decision.outcome(),
                decision.method(),
                decision.source(),
                decision.trustedTransactionId(),
                decision.reasons(),
                decision.detail(),
                actorId,
                latest);
    }

    private PaymentVerificationCheck append(
            MemberPayment payment,
            VerificationOutcome outcome,
            VerificationMethod method,
            VerificationSource source,
            UUID trustedTransactionId,
            List<String> reasons,
            String detail,
            UUID checkedBy,
            PaymentVerificationCheck latest) {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        if (latest != null && !now.isAfter(latest.getCreatedAt())) {
            now = latest.getCreatedAt().plus(1, ChronoUnit.MICROS); // the history is strictly ordered
        }
        return checkRepository.saveAndFlush(PaymentVerificationCheck.builder()
                .cooperativeId(payment.getCooperativeId())
                .paymentId(payment.getId())
                .method(method)
                .outcome(outcome)
                .source(source)
                .trustedTransactionId(trustedTransactionId)
                .reasonCodes(reasons.isEmpty() ? null : String.join(",", reasons))
                .detail(trim(detail, 1000))
                .checkedBy(checkedBy)
                .createdAt(now)
                .build());
    }

    private static boolean sameOutcome(PaymentVerificationCheck latest, Decision d) {
        return latest.getOutcome() == d.outcome()
                && latest.getMethod() == d.method()
                && latest.getSource() == d.source()
                && Objects.equals(latest.getTrustedTransactionId(), d.trustedTransactionId())
                && Objects.equals(latest.getReasonCodes(), d.reasons().isEmpty() ? null : String.join(",", d.reasons()));
    }

    private java.util.Optional<PaymentVerificationCheck> latest(MemberPayment payment) {
        return checkRepository.findFirstByCooperativeIdAndPaymentIdOrderByCreatedAtDesc(
                payment.getCooperativeId(), payment.getId());
    }

    private MemberPayment lockPayment(UUID cooperativeId, UUID paymentId) {
        return paymentRepository
                .findByIdAndCooperativeIdForUpdate(paymentId, cooperativeId)
                .orElseThrow(() -> new ResourceNotFoundException("Payment", paymentId));
    }

    private void audit(UUID actorId, PaymentVerificationCheck check) {
        audit(actorId, check, AuditableAction.VERIFICATION_CHECK_RECORDED);
    }

    private void audit(UUID actorId, PaymentVerificationCheck check, AuditableAction action) {
        auditService.record(
                actorId,
                check.getCooperativeId(),
                action,
                "MemberPayment",
                check.getPaymentId(),
                null,
                "{\"outcome\":\"" + check.getOutcome() + "\",\"method\":\"" + check.getMethod() + "\",\"source\":\""
                        + check.getSource() + "\"}",
                null,
                null);
    }

    private UserPrincipal requireStaff(UUID cooperativeId) {
        authorizationService.requireMembership(cooperativeId);
        UserPrincipal actor = authorizationService.currentPrincipal();
        if (!actor.hasRole(CooperativeAuthorizationService.SUPER_ADMIN)
                && !actor.hasAuthority(CONTRIBUTION_WRITE)
                && !actor.hasAuthority(LOAN_WRITE)) {
            throw new ForbiddenException("You do not have permission to check payment evidence");
        }
        return actor;
    }

    private static List<String> splitReasons(String codes) {
        return StringUtils.hasText(codes) ? Arrays.asList(codes.split(",")) : List.of();
    }

    private static String trim(String value, int max) {
        if (value == null) {
            return null;
        }
        String t = value.trim();
        return t.length() > max ? t.substring(0, max) : t;
    }

    // ------------------------------------------------------------------ DTOs

    public record CheckResponse(
            UUID id,
            UUID paymentId,
            VerificationOutcome outcome,
            VerificationMethod method,
            VerificationSource source,
            boolean authoritative,
            UUID trustedTransactionId,
            List<String> reasons,
            String detail,
            UUID checkedBy,
            Instant createdAt) {
        static CheckResponse from(PaymentVerificationCheck c) {
            return new CheckResponse(
                    c.getId(),
                    c.getPaymentId(),
                    c.getOutcome(),
                    c.getMethod(),
                    c.getSource(),
                    c.getSource().authoritative(),
                    c.getTrustedTransactionId(),
                    splitReasons(c.getReasonCodes()),
                    c.getDetail(),
                    c.getCheckedBy(),
                    c.getCreatedAt());
        }
    }

    /**
     * @param requiresTrustedConfirmation whether this deployment refuses to verify without authoritative evidence
     * @param automaticProviderConfirmation UNAVAILABLE, or CALLBACK_CONFIGURED when a signed callback secret exists
     *     (polling a provider is not implemented)
     */
    public record Overview(
            boolean requiresTrustedConfirmation,
            String automaticProviderConfirmation,
            CheckResponse current,
            List<CheckResponse> history) {}

    public record AttentionItem(
            UUID paymentId,
            UUID memberUserId,
            BigDecimal amount,
            String currency,
            PaymentMethod paymentMethod,
            String transactionReference,
            PaymentStatus paymentStatus,
            VerificationOutcome outcome,
            List<String> reasons,
            Instant checkedAt) {}
}
