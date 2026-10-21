package rw.terimbere.csams.modules.contribution.obligation;

import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
import java.time.Instant;
import java.time.YearMonth;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import rw.terimbere.csams.modules.audit.service.AuditService;
import rw.terimbere.csams.modules.contribution.obligation.ObligationDtos.DueDayEntry;
import rw.terimbere.csams.modules.contribution.obligation.ObligationDtos.DueDayRequest;
import rw.terimbere.csams.modules.contribution.obligation.ObligationDtos.DueDayResponse;
import rw.terimbere.csams.modules.cooperative.repository.CooperativeRepository;
import rw.terimbere.csams.modules.fundlock.SchemeFundLockService;
import rw.terimbere.csams.security.CooperativeAuthorizationService;
import rw.terimbere.csams.security.CooperativeOfficerRoles;
import rw.terimbere.csams.security.UserPrincipal;
import rw.terimbere.csams.shared.auditing.AuditableAction;
import rw.terimbere.csams.shared.exceptions.ConflictException;
import rw.terimbere.csams.shared.exceptions.ForbiddenException;
import rw.terimbere.csams.shared.exceptions.ResourceNotFoundException;

/**
 * The monthly contribution due day, effective-dated and insert-only.
 *
 * <p>A scheme with no setting has NO configured due day and produces no overdue dates: the legacy due-day column on the
 * scheme defaults to 1 for every scheme, so it cannot distinguish "chosen" from "never set" and is not used for arrears
 * (the automatic late-contribution fines still read that legacy column and are unchanged). A setting always takes
 * effect from the first day of the NEXT month, so it can never backdate arrears, and obligations already created keep the
 * due date they were created with.
 */
@Service
@RequiredArgsConstructor
public class ContributionDueDayService {

    private static final String WRITE_AUTHORITY = "COOPERATIVE_WRITE";

    private final ContributionDueDaySettingRepository repository;
    private final CooperativeRepository cooperativeRepository;
    private final CooperativeAuthorizationService authorizationService;
    private final ContributionObligationService obligationService;
    private final SchemeFundLockService fundLockService;
    private final AuditService auditService;
    private final Clock clock;

    @Transactional(readOnly = true)
    public DueDayResponse view(UUID cooperativeId) {
        requireScheme(cooperativeId);
        authorizationService.requireMembership(cooperativeId);
        return buildView(cooperativeId);
    }

    /**
     * Sets the due day from next month on. Requires Super Admin, or the scheme's President or Vice President with
     * COOPERATIVE_WRITE, and an explicit confirmation. An identical repeat is a success; a different value for a month
     * that already has a pending change is refused (settings are insert-only).
     */
    @Transactional
    public DueDayResponse set(UUID cooperativeId, DueDayRequest request, HttpServletRequest http) {
        requireScheme(cooperativeId);
        UserPrincipal principal = requireManage(cooperativeId);
        fundLockService.lockSchemeFund(cooperativeId);

        YearMonth effective = ContributionDueDates.currentMonth(clock).plusMonths(1);
        Optional<ContributionDueDaySetting> existing = repository.findByCooperativeIdAndEffectiveYearAndEffectiveMonth(
                cooperativeId, effective.getYear(), effective.getMonthValue());
        if (existing.isPresent()) {
            if (existing.get().getDueDay() == request.dueDay()) {
                return buildView(cooperativeId);
            }
            throw new ConflictException(
                    "CONTRIBUTION_DUE_DAY_PENDING",
                    "A different due day is already scheduled from " + effective + " and cannot be changed");
        }
        ContributionDueDaySetting saved = insert(cooperativeId, effective, request.dueDay(), principal.getId());
        auditService.record(
                principal.getId(),
                cooperativeId,
                AuditableAction.CONTRIBUTION_DUE_DAY_SET,
                "ContributionDueDaySetting",
                saved.getId(),
                null,
                "{\"effective\":\"" + effective + "\",\"dueDay\":" + saved.getDueDay() + "}",
                clientIp(http),
                http == null ? null : http.getHeader("User-Agent"));
        return buildView(cooperativeId);
    }

    /**
     * A new scheme registers its due day (which the registration form requires) as the first setting, effective from
     * the next month so the registration month never produces an overdue date. Runs in the creation transaction.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void seedForNewScheme(UUID cooperativeId, int dueDay, UUID actorId) {
        insert(cooperativeId, ContributionDueDates.currentMonth(clock).plusMonths(1), dueDay, actorId);
    }

    /**
     * An ordinary scheme edit changed the legacy due-day field. For a scheme that already has a configured due day the
     * new value is scheduled from next month; a legacy scheme with none is NOT silently given one by an edit (that would
     * confirm a day nobody chose). An identical value, or a different change already pending for next month, is left alone.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void onSchemeEdited(UUID cooperativeId, int newDueDay, UUID actorId) {
        if (!repository.existsByCooperativeId(cooperativeId) || newDueDay < 1 || newDueDay > 31) {
            return;
        }
        YearMonth effective = ContributionDueDates.currentMonth(clock).plusMonths(1);
        if (repository.findByCooperativeIdAndEffectiveYearAndEffectiveMonth(
                        cooperativeId, effective.getYear(), effective.getMonthValue())
                .isPresent()) {
            return;
        }
        Integer inForce = repository.findByCooperativeIdOrderByEffectiveYearDescEffectiveMonthDesc(cooperativeId).stream()
                .filter(s -> s.getEffectiveYear() * 12 + s.getEffectiveMonth() <= effective.getYear() * 12 + effective.getMonthValue())
                .map(ContributionDueDaySetting::getDueDay)
                .findFirst()
                .orElse(null);
        if (inForce == null || inForce != newDueDay) {
            insert(cooperativeId, effective, newDueDay, actorId);
        }
    }

    private ContributionDueDaySetting insert(UUID cooperativeId, YearMonth effective, int dueDay, UUID actorId) {
        ContributionDueDaySetting saved = repository.saveAndFlush(ContributionDueDaySetting.builder()
                .cooperativeId(cooperativeId)
                .effectiveYear(effective.getYear())
                .effectiveMonth(effective.getMonthValue())
                .dueDay(dueDay)
                .createdBy(actorId)
                .createdAt(Instant.now(clock))
                .build());
        obligationService.evict(cooperativeId);
        return saved;
    }

    private DueDayResponse buildView(UUID cooperativeId) {
        YearMonth current = ContributionDueDates.currentMonth(clock);
        List<ContributionDueDaySetting> all =
                repository.findByCooperativeIdOrderByEffectiveYearDescEffectiveMonthDesc(cooperativeId);
        int now = current.getYear() * 12 + current.getMonthValue();
        Integer inForce = null;
        DueDayEntry pending = null;
        for (ContributionDueDaySetting s : all) {
            int effective = s.getEffectiveYear() * 12 + s.getEffectiveMonth();
            if (effective > now) {
                pending = toEntry(s); // newest first: the last one assigned is the earliest pending
            } else if (inForce == null) {
                inForce = s.getDueDay();
            }
        }
        return new DueDayResponse(!all.isEmpty(), inForce, pending, all.stream().map(this::toEntry).toList());
    }

    private DueDayEntry toEntry(ContributionDueDaySetting s) {
        return new DueDayEntry(s.getEffectiveYear(), s.getEffectiveMonth(), s.getDueDay(), s.getCreatedAt());
    }

    private UserPrincipal requireManage(UUID cooperativeId) {
        UserPrincipal principal = authorizationService.currentPrincipal();
        if (principal.hasRole(CooperativeAuthorizationService.SUPER_ADMIN) && principal.hasAuthority(WRITE_AUTHORITY)) {
            return principal;
        }
        authorizationService.requireMembership(cooperativeId);
        if (!CooperativeOfficerRoles.isLeadership(principal) || !principal.hasAuthority(WRITE_AUTHORITY)) {
            throw new ForbiddenException("President or Vice President with COOPERATIVE_WRITE required");
        }
        return principal;
    }

    private void requireScheme(UUID cooperativeId) {
        if (cooperativeId == null || cooperativeRepository.findByIdAndDeletedFalse(cooperativeId).isEmpty()) {
            throw new ResourceNotFoundException("Cooperative", cooperativeId);
        }
    }

    private static String clientIp(HttpServletRequest request) {
        if (request == null) {
            return null;
        }
        String forwarded = request.getHeader("X-Forwarded-For");
        return StringUtils.hasText(forwarded) ? forwarded.split(",")[0].trim() : request.getRemoteAddr();
    }
}
