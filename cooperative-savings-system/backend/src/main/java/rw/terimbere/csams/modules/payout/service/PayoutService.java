package rw.terimbere.csams.modules.payout.service;

import jakarta.servlet.http.HttpServletRequest;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import rw.terimbere.csams.modules.audit.service.AuditService;
import rw.terimbere.csams.modules.contribution.repository.ContributionRepository;
import rw.terimbere.csams.modules.notification.entity.NotificationType;
import rw.terimbere.csams.modules.notification.service.NotificationFacade;
import rw.terimbere.csams.modules.cooperative.entity.Cooperative;
import rw.terimbere.csams.modules.cooperative.repository.CooperativeRepository;
import rw.terimbere.csams.modules.cooperative.service.SchemeOperatingModelGuard;
import rw.terimbere.csams.modules.fundlock.SchemeFundLockService;
import rw.terimbere.csams.modules.ledger.service.LedgerService;
import rw.terimbere.csams.modules.payout.dto.PayoutLineResponse;
import rw.terimbere.csams.modules.payout.dto.PayoutMarkPaidRequest;
import rw.terimbere.csams.modules.payout.dto.PayoutPreviewRequest;
import rw.terimbere.csams.modules.payout.dto.PayoutRunResponse;
import rw.terimbere.csams.modules.payout.dto.PayoutStatementResponse;
import rw.terimbere.csams.modules.payout.entity.PayoutLine;
import rw.terimbere.csams.modules.payout.entity.PayoutLineStatus;
import rw.terimbere.csams.modules.payout.entity.PayoutRun;
import rw.terimbere.csams.modules.payout.entity.PayoutRunStatus;
import rw.terimbere.csams.modules.payout.repository.PayoutLineRepository;
import rw.terimbere.csams.modules.payout.repository.PayoutRunRepository;
import rw.terimbere.csams.modules.specialcontribution.repository.SpecialContributionRepository;
import rw.terimbere.csams.security.CooperativeAuthorizationService;
import rw.terimbere.csams.security.CooperativeOfficerRoles;
import rw.terimbere.csams.security.UserPrincipal;
import rw.terimbere.csams.shared.auditing.AuditableAction;
import rw.terimbere.csams.shared.common.dto.PageResponse;
import rw.terimbere.csams.shared.exceptions.BusinessException;
import rw.terimbere.csams.shared.exceptions.ResourceNotFoundException;
import rw.terimbere.csams.shared.exceptions.ValidationException;
import rw.terimbere.csams.shared.financial.LedgerFinancialCalculationService;
import rw.terimbere.csams.shared.financial.LedgerTransactionType;
import rw.terimbere.csams.shared.pagination.PageMapper;
import rw.terimbere.csams.shared.utilities.MoneyUtils;

@Service
@RequiredArgsConstructor
public class PayoutService {

    public static final int PERCENTAGE_SCALE = 8;

    private final PayoutRunRepository payoutRunRepository;
    private final PayoutLineRepository payoutLineRepository;
    private final ContributionRepository contributionRepository;
    private final SpecialContributionRepository specialContributionRepository;
    private final CooperativeRepository cooperativeRepository;
    private final LedgerService ledgerService;
    private final LedgerFinancialCalculationService financialCalculationService;
    private final CooperativeAuthorizationService authorizationService;
    private final AuditService auditService;
    private final NotificationFacade notificationFacade;
    private final SchemeFundLockService schemeFundLockService;
    private final SchemeOperatingModelGuard operatingModelGuard;

    @Transactional
    public PayoutRunResponse preview(
            UUID cooperativeId, PayoutPreviewRequest request, HttpServletRequest httpRequest) {
        Cooperative cooperative = requireCooperative(cooperativeId);
        UserPrincipal principal = authorizationService.currentPrincipal();
        authorizationService.requireMembership(cooperativeId);

        boolean includeRegular = Boolean.TRUE.equals(request.getIncludeRegular());
        boolean includeSpecial = Boolean.TRUE.equals(request.getIncludeSpecial());
        if (!includeRegular && !includeSpecial) {
            throw new ValidationException("At least one of includeRegular or includeSpecial must be true");
        }

        LocalDate[] period = resolvePeriod(request);
        LocalDate periodFrom = period[0];
        LocalDate periodTo = period[1];

        BigDecimal available = financialCalculationService.calculateAvailableGroupFund(cooperativeId);
        BigDecimal pool;
        if (request.getPayoutPoolAmount() == null) {
            pool = MoneyUtils.scaleForStorage(available);
        } else {
            pool = MoneyUtils.scaleForStorage(request.getPayoutPoolAmount());
            MoneyUtils.assertPositive(pool);
            if (MoneyUtils.scale(pool).compareTo(available) > 0) {
                throw new BusinessException(
                        "INSUFFICIENT_GROUP_FUND",
                        "Payout pool exceeds available group fund. Available: "
                                + available
                                + ", pool: "
                                + MoneyUtils.scale(pool));
            }
        }
        if (MoneyUtils.isZero(MoneyUtils.scale(pool))) {
            throw new BusinessException("Payout pool must be positive");
        }

        Map<UUID, BigDecimal> eligibleByMember =
                accumulateEligible(cooperativeId, periodFrom, periodTo, includeRegular, includeSpecial);
        if (eligibleByMember.isEmpty()) {
            throw new BusinessException("No eligible contributions found for the selected period");
        }

        BigDecimal totalEligible = BigDecimal.ZERO.setScale(MoneyUtils.STORAGE_SCALE, RoundingMode.HALF_UP);
        for (BigDecimal amount : eligibleByMember.values()) {
            totalEligible = totalEligible.add(amount);
        }
        totalEligible = MoneyUtils.scaleForStorage(totalEligible);
        if (MoneyUtils.isZero(MoneyUtils.scale(totalEligible))) {
            throw new BusinessException("Total eligible contributions must be positive");
        }

        List<AllocatedLine> allocated = allocate(eligibleByMember, totalEligible, pool);

        PayoutRun run = PayoutRun.builder()
                .cooperativeId(cooperativeId)
                .name(trimToNull(request.getName()))
                .periodFrom(periodFrom)
                .periodTo(periodTo)
                .includeRegular(includeRegular)
                .includeSpecial(includeSpecial)
                .availableFundSnapshot(MoneyUtils.scaleForStorage(available))
                .payoutPoolAmount(pool)
                .totalEligibleContributions(totalEligible)
                .currency(cooperative.getCurrency())
                .status(PayoutRunStatus.PREVIEWED)
                .createdBy(principal.getId())
                .notes(trimToNull(request.getNotes()))
                .build();
        run = payoutRunRepository.save(run);

        List<PayoutLine> lines = new ArrayList<>(allocated.size());
        for (AllocatedLine a : allocated) {
            lines.add(PayoutLine.builder()
                    .payoutRunId(run.getId())
                    .cooperativeId(cooperativeId)
                    .memberUserId(a.memberUserId())
                    .eligibleContributionAmount(a.eligible())
                    .percentage(a.percentage())
                    .payoutAmount(a.payout())
                    .status(PayoutLineStatus.PENDING)
                    .build());
        }
        lines = payoutLineRepository.saveAll(lines);

        auditService.record(
                principal.getId(),
                cooperativeId,
                AuditableAction.PAYOUT_PREVIEW,
                "PayoutRun",
                run.getId(),
                null,
                "{\"status\":\"PREVIEWED\",\"pool\":\"" + pool + "\",\"lines\":" + lines.size() + "}",
                clientIp(httpRequest),
                userAgent(httpRequest));

        return toRunResponse(run, lines);
    }

    @Transactional(readOnly = true)
    public PageResponse<PayoutRunResponse> list(UUID cooperativeId, PayoutRunStatus status, Pageable pageable) {
        requireCooperative(cooperativeId);
        authorizationService.requireMembership(cooperativeId);
        Page<PayoutRun> page = status == null
                ? payoutRunRepository.findByCooperativeId(cooperativeId, pageable)
                : payoutRunRepository.findByCooperativeIdAndStatus(cooperativeId, status, pageable);
        return PageMapper.toPageResponse(page, run -> toRunResponse(run, null));
    }

    @Transactional(readOnly = true)
    public PayoutRunResponse get(UUID cooperativeId, UUID runId) {
        requireCooperative(cooperativeId);
        UserPrincipal principal = authorizationService.currentPrincipal();
        authorizationService.requireMembership(cooperativeId);
        PayoutRun run = requireRun(cooperativeId, runId);
        List<PayoutLine> lines =
                payoutLineRepository.findByPayoutRunIdAndCooperativeIdOrderByMemberUserIdAsc(runId, cooperativeId);
        if (!canSeeAllLines(principal)) {
            lines = lines.stream()
                    .filter(l -> l.getMemberUserId().equals(principal.getId()))
                    .toList();
        }
        return toRunResponse(run, lines);
    }

    @Transactional(readOnly = true)
    public List<PayoutLineResponse> myLines(UUID cooperativeId) {
        requireCooperative(cooperativeId);
        UserPrincipal principal = authorizationService.currentPrincipal();
        authorizationService.requireMembership(cooperativeId);
        return payoutLineRepository
                .findByCooperativeIdAndMemberUserIdOrderByCreatedAtDesc(cooperativeId, principal.getId())
                .stream()
                .map(this::toLineResponse)
                .toList();
    }

    @Transactional
    public PayoutRunResponse confirm(UUID cooperativeId, UUID runId, HttpServletRequest httpRequest) {
        Cooperative cooperative = requireCooperative(cooperativeId);
        UserPrincipal principal = authorizationService.currentPrincipal();
        authorizationService.requireMembership(cooperativeId);
        CooperativeOfficerRoles.requireFundAuthorize(principal);
        // Serialize with every other operation that spends this scheme's funds, before the run is read for its final
        // decision and before available funds are checked (a preview is advisory and reserves nothing).
        schemeFundLockService.lockSchemeFund(cooperativeId);
        operatingModelGuard.requireAccumulatingWorkflows(cooperativeId, "Payout confirmation");
        PayoutRun run = requireRun(cooperativeId, runId);

        if (run.getStatus() != PayoutRunStatus.PREVIEWED) {
            throw new BusinessException("Only PREVIEWED payout runs can be confirmed");
        }

        BigDecimal pool = MoneyUtils.scale(run.getPayoutPoolAmount());
        BigDecimal available = financialCalculationService.calculateAvailableGroupFund(cooperativeId);
        if (available.compareTo(pool) < 0) {
            throw new BusinessException(
                    "INSUFFICIENT_GROUP_FUND",
                    "Available group fund is insufficient to confirm payout. Available: "
                            + available
                            + ", pool: "
                            + pool);
        }

        List<PayoutLine> lines =
                payoutLineRepository.findByPayoutRunIdAndCooperativeIdOrderByMemberUserIdAsc(runId, cooperativeId);
        Instant now = Instant.now();
        LocalDate today = LocalDate.now();

        for (PayoutLine line : lines) {
            BigDecimal amount = MoneyUtils.scaleForStorage(line.getPayoutAmount());
            if (MoneyUtils.isZero(MoneyUtils.scale(amount))) {
                line.setStatus(PayoutLineStatus.CONFIRMED);
                continue;
            }
            ledgerService.appendApproved(LedgerService.AppendRequest.builder()
                    .cooperativeId(cooperativeId)
                    .memberUserId(line.getMemberUserId())
                    .transactionType(LedgerTransactionType.MEMBER_PAYOUT)
                    .debitAmount(amount)
                    .creditAmount(BigDecimal.ZERO)
                    .currency(cooperative.getCurrency())
                    .transactionDate(today)
                    .reference("PAYOUT-" + run.getId())
                    .sourceEntityType(LedgerService.SOURCE_MEMBER_PAYOUT)
                    .sourceEntityId(line.getId())
                    .description("Member payout for run " + run.getId())
                    .recordedBy(principal.getId())
                    .approvedBy(principal.getId())
                    .idempotencyKey(LedgerService.memberPayoutKey(line.getId()))
                    .build());
            line.setStatus(PayoutLineStatus.CONFIRMED);
        }
        payoutLineRepository.saveAll(lines);

        run.setStatus(PayoutRunStatus.CONFIRMED);
        run.setConfirmedAt(now);
        run.setConfirmedBy(principal.getId());
        run = payoutRunRepository.save(run);

        auditService.record(
                principal.getId(),
                cooperativeId,
                AuditableAction.PAYOUT_CONFIRM,
                "PayoutRun",
                run.getId(),
                "{\"status\":\"PREVIEWED\"}",
                "{\"status\":\"CONFIRMED\",\"pool\":\"" + run.getPayoutPoolAmount() + "\"}",
                clientIp(httpRequest),
                userAgent(httpRequest));

        for (PayoutLine line : lines) {
            if (MoneyUtils.isZero(MoneyUtils.scale(line.getPayoutAmount()))) {
                continue;
            }
            notificationFacade.notifyUser(
                    line.getMemberUserId(),
                    cooperativeId,
                    NotificationType.PAYOUT,
                    "Payout confirmed",
                    "A payout of " + MoneyUtils.scale(line.getPayoutAmount()) + " has been confirmed for you.",
                    "PayoutRun",
                    run.getId());
        }

        return toRunResponse(run, lines);
    }

    @Transactional
    public PayoutRunResponse markPaid(
            UUID cooperativeId, UUID runId, PayoutMarkPaidRequest request, HttpServletRequest httpRequest) {
        requireCooperative(cooperativeId);
        UserPrincipal principal = authorizationService.currentPrincipal();
        authorizationService.requireMembership(cooperativeId);
        PayoutRun run = requireRun(cooperativeId, runId);

        if (run.getStatus() != PayoutRunStatus.CONFIRMED && run.getStatus() != PayoutRunStatus.PAID) {
            throw new BusinessException("Only CONFIRMED (or partially paid) payout runs can be marked paid");
        }

        List<PayoutLine> allLines =
                payoutLineRepository.findByPayoutRunIdAndCooperativeIdOrderByMemberUserIdAsc(runId, cooperativeId);
        List<PayoutLine> toMark;
        if (request != null && request.getLineIds() != null && !request.getLineIds().isEmpty()) {
            toMark = payoutLineRepository.findByPayoutRunIdAndIdIn(runId, request.getLineIds());
            if (toMark.size() != request.getLineIds().stream().distinct().count()) {
                throw new ValidationException("One or more payout lines were not found for this run");
            }
        } else {
            toMark = allLines;
        }

        for (PayoutLine line : toMark) {
            if (line.getStatus() == PayoutLineStatus.PENDING) {
                throw new BusinessException("Cannot mark unpaid (unconfirmed) payout lines as paid");
            }
            line.setStatus(PayoutLineStatus.PAID);
        }
        payoutLineRepository.saveAll(toMark);

        allLines =
                payoutLineRepository.findByPayoutRunIdAndCooperativeIdOrderByMemberUserIdAsc(runId, cooperativeId);
        boolean allPaid = allLines.stream().allMatch(l -> l.getStatus() == PayoutLineStatus.PAID);
        if (allPaid) {
            run.setStatus(PayoutRunStatus.PAID);
            run.setPaidAt(Instant.now());
            run.setPaidBy(principal.getId());
            run = payoutRunRepository.save(run);
        }

        auditService.record(
                principal.getId(),
                cooperativeId,
                AuditableAction.PAYOUT_PAID,
                "PayoutRun",
                run.getId(),
                null,
                "{\"status\":\"" + run.getStatus() + "\",\"markedLines\":" + toMark.size() + "}",
                clientIp(httpRequest),
                userAgent(httpRequest));

        return toRunResponse(run, allLines);
    }

    @Transactional
    public PayoutRunResponse cancel(UUID cooperativeId, UUID runId, HttpServletRequest httpRequest) {
        requireCooperative(cooperativeId);
        UserPrincipal principal = authorizationService.currentPrincipal();
        authorizationService.requireMembership(cooperativeId);
        PayoutRun run = requireRun(cooperativeId, runId);

        if (run.getStatus() != PayoutRunStatus.DRAFT && run.getStatus() != PayoutRunStatus.PREVIEWED) {
            throw new BusinessException("Only DRAFT or PREVIEWED payout runs can be cancelled");
        }

        String previous = "{\"status\":\"" + run.getStatus() + "\"}";
        run.setStatus(PayoutRunStatus.CANCELLED);
        run = payoutRunRepository.save(run);

        auditService.record(
                principal.getId(),
                cooperativeId,
                AuditableAction.PAYOUT_CANCEL,
                "PayoutRun",
                run.getId(),
                previous,
                "{\"status\":\"CANCELLED\"}",
                clientIp(httpRequest),
                userAgent(httpRequest));

        List<PayoutLine> lines =
                payoutLineRepository.findByPayoutRunIdAndCooperativeIdOrderByMemberUserIdAsc(runId, cooperativeId);
        return toRunResponse(run, lines);
    }

    @Transactional(readOnly = true)
    public PayoutStatementResponse statement(UUID cooperativeId, UUID runId) {
        Cooperative cooperative = requireCooperative(cooperativeId);
        UserPrincipal principal = authorizationService.currentPrincipal();
        authorizationService.requireMembership(cooperativeId);
        PayoutRun run = requireRun(cooperativeId, runId);
        List<PayoutLine> lines =
                payoutLineRepository.findByPayoutRunIdAndCooperativeIdOrderByMemberUserIdAsc(runId, cooperativeId);
        if (!canSeeAllLines(principal)) {
            lines = lines.stream()
                    .filter(l -> l.getMemberUserId().equals(principal.getId()))
                    .toList();
        }

        BigDecimal totalPayout = BigDecimal.ZERO.setScale(MoneyUtils.STORAGE_SCALE, RoundingMode.HALF_UP);
        for (PayoutLine line : lines) {
            totalPayout = totalPayout.add(MoneyUtils.scaleForStorage(line.getPayoutAmount()));
        }

        return PayoutStatementResponse.builder()
                .runId(run.getId())
                .cooperativeName(cooperative.getName())
                .name(run.getName())
                .periodFrom(run.getPeriodFrom())
                .periodTo(run.getPeriodTo())
                .generatedAt(Instant.now())
                .status(run.getStatus())
                .currency(run.getCurrency())
                .availableFundSnapshot(MoneyUtils.scale(run.getAvailableFundSnapshot()))
                .payoutPoolAmount(MoneyUtils.scale(run.getPayoutPoolAmount()))
                .totalEligibleContributions(MoneyUtils.scale(run.getTotalEligibleContributions()))
                .totalPayoutAmount(MoneyUtils.scale(totalPayout))
                .lines(lines.stream().map(this::toLineResponse).toList())
                .build();
    }

    @Transactional(readOnly = true)
    public long countPendingPreviews(UUID cooperativeId) {
        return payoutRunRepository.countByCooperativeIdAndStatus(cooperativeId, PayoutRunStatus.PREVIEWED);
    }

    @Transactional(readOnly = true)
    public BigDecimal sumConfirmedPayoutPools(UUID cooperativeId) {
        BigDecimal sum = payoutRunRepository.sumPoolByStatuses(
                cooperativeId, EnumSet.of(PayoutRunStatus.CONFIRMED, PayoutRunStatus.PAID));
        return MoneyUtils.scale(sum == null ? BigDecimal.ZERO : sum);
    }

    @Transactional(readOnly = true)
    public List<PayoutLineResponse> recentForMember(UUID cooperativeId, UUID memberUserId) {
        return payoutLineRepository
                .findTop20ByCooperativeIdAndMemberUserIdOrderByCreatedAtDesc(cooperativeId, memberUserId)
                .stream()
                .map(this::toLineResponse)
                .toList();
    }

    private Map<UUID, BigDecimal> accumulateEligible(
            UUID cooperativeId,
            LocalDate periodFrom,
            LocalDate periodTo,
            boolean includeRegular,
            boolean includeSpecial) {
        Map<UUID, BigDecimal> map = new HashMap<>();
        if (includeRegular) {
            for (Object[] row :
                    contributionRepository.sumPaidGroupedByMemberInDateRange(cooperativeId, periodFrom, periodTo)) {
                UUID memberId = (UUID) row[0];
                BigDecimal amount = MoneyUtils.scaleForStorage((BigDecimal) row[1]);
                map.merge(memberId, amount, (a, b) -> MoneyUtils.scaleForStorage(a.add(b)));
            }
        }
        if (includeSpecial) {
            for (Object[] row : specialContributionRepository.sumApprovedGroupedByMemberInDateRange(
                    cooperativeId, periodFrom, periodTo)) {
                UUID memberId = (UUID) row[0];
                BigDecimal amount = MoneyUtils.scaleForStorage((BigDecimal) row[1]);
                map.merge(memberId, amount, (a, b) -> MoneyUtils.scaleForStorage(a.add(b)));
            }
        }
        map.entrySet().removeIf(e -> MoneyUtils.isZero(MoneyUtils.scale(e.getValue())));
        return map;
    }

    /**
     * Allocates pool by eligible share. Percentages use {@link #PERCENTAGE_SCALE}; money uses storage
     * scale. Last member absorbs remainder so line payouts sum exactly to the pool.
     */
    public List<AllocatedLine> allocate(
            Map<UUID, BigDecimal> eligibleByMember, BigDecimal totalEligible, BigDecimal pool) {
        TreeMap<UUID, BigDecimal> ordered = new TreeMap<>(eligibleByMember);
        List<UUID> memberIds = new ArrayList<>(ordered.keySet());
        List<AllocatedLine> result = new ArrayList<>(memberIds.size());
        BigDecimal distributed = BigDecimal.ZERO.setScale(MoneyUtils.STORAGE_SCALE, RoundingMode.HALF_UP);
        BigDecimal hundred = new BigDecimal("100");

        for (int i = 0; i < memberIds.size(); i++) {
            UUID memberId = memberIds.get(i);
            BigDecimal eligible = MoneyUtils.scaleForStorage(ordered.get(memberId));
            BigDecimal percentage = eligible
                    .multiply(hundred)
                    .divide(totalEligible, PERCENTAGE_SCALE, RoundingMode.HALF_UP);

            BigDecimal payout;
            if (i == memberIds.size() - 1) {
                payout = MoneyUtils.scaleForStorage(pool.subtract(distributed));
            } else {
                payout = pool.multiply(eligible)
                        .divide(totalEligible, MoneyUtils.STORAGE_SCALE, RoundingMode.HALF_UP);
                distributed = MoneyUtils.scaleForStorage(distributed.add(payout));
            }
            result.add(new AllocatedLine(memberId, eligible, percentage, payout));
        }
        return result;
    }

    private LocalDate[] resolvePeriod(PayoutPreviewRequest request) {
        if (request.getPeriodFrom() != null || request.getPeriodTo() != null) {
            if (request.getPeriodFrom() == null || request.getPeriodTo() == null) {
                throw new ValidationException("Both periodFrom and periodTo are required when using date range");
            }
            if (request.getPeriodFrom().isAfter(request.getPeriodTo())) {
                throw new ValidationException("periodFrom must be on or before periodTo");
            }
            return new LocalDate[] {request.getPeriodFrom(), request.getPeriodTo()};
        }

        Integer fromYear = request.getFromYear();
        Integer toYear = request.getToYear();
        Integer fromMonth = request.getFromMonth();
        Integer toMonth = request.getToMonth();

        if (fromYear == null || toYear == null) {
            throw new ValidationException(
                    "Provide periodFrom/periodTo, or fromYear/toYear (optionally with fromMonth/toMonth)");
        }
        if (fromYear > toYear) {
            throw new ValidationException("fromYear must be <= toYear");
        }

        if (fromMonth != null || toMonth != null) {
            if (fromMonth == null || toMonth == null) {
                throw new ValidationException("Both fromMonth and toMonth are required for month range");
            }
            YearMonth fromYm = YearMonth.of(fromYear, fromMonth);
            YearMonth toYm = YearMonth.of(toYear, toMonth);
            if (fromYm.isAfter(toYm)) {
                throw new ValidationException("fromYear/fromMonth must be on or before toYear/toMonth");
            }
            return new LocalDate[] {fromYm.atDay(1), toYm.atEndOfMonth()};
        }

        return new LocalDate[] {LocalDate.of(fromYear, 1, 1), LocalDate.of(toYear, 12, 31)};
    }

    private boolean canSeeAllLines(UserPrincipal principal) {
        return principal.hasAuthority("PAYOUT_WRITE")
                || principal.hasRole(CooperativeAuthorizationService.SUPER_ADMIN);
    }

    private Cooperative requireCooperative(UUID cooperativeId) {
        return cooperativeRepository
                .findByIdAndDeletedFalse(cooperativeId)
                .orElseThrow(() -> new ResourceNotFoundException("Cooperative", cooperativeId));
    }

    private PayoutRun requireRun(UUID cooperativeId, UUID runId) {
        return payoutRunRepository
                .findByIdAndCooperativeId(runId, cooperativeId)
                .orElseThrow(() -> new ResourceNotFoundException("PayoutRun", runId));
    }

    private PayoutRunResponse toRunResponse(PayoutRun run, List<PayoutLine> lines) {
        return PayoutRunResponse.builder()
                .id(run.getId())
                .cooperativeId(run.getCooperativeId())
                .name(run.getName())
                .periodFrom(run.getPeriodFrom())
                .periodTo(run.getPeriodTo())
                .includeRegular(run.isIncludeRegular())
                .includeSpecial(run.isIncludeSpecial())
                .availableFundSnapshot(MoneyUtils.scale(run.getAvailableFundSnapshot()))
                .payoutPoolAmount(MoneyUtils.scale(run.getPayoutPoolAmount()))
                .totalEligibleContributions(MoneyUtils.scale(run.getTotalEligibleContributions()))
                .currency(run.getCurrency())
                .status(run.getStatus())
                .confirmedAt(run.getConfirmedAt())
                .confirmedBy(run.getConfirmedBy())
                .paidAt(run.getPaidAt())
                .paidBy(run.getPaidBy())
                .createdBy(run.getCreatedBy())
                .notes(run.getNotes())
                .createdAt(run.getCreatedAt())
                .updatedAt(run.getUpdatedAt())
                .lines(lines == null ? null : lines.stream().map(this::toLineResponse).toList())
                .build();
    }

    private PayoutLineResponse toLineResponse(PayoutLine line) {
        return PayoutLineResponse.builder()
                .id(line.getId())
                .payoutRunId(line.getPayoutRunId())
                .cooperativeId(line.getCooperativeId())
                .memberUserId(line.getMemberUserId())
                .eligibleContributionAmount(MoneyUtils.scale(line.getEligibleContributionAmount()))
                .percentage(line.getPercentage())
                .payoutAmount(MoneyUtils.scale(line.getPayoutAmount()))
                .status(line.getStatus())
                .createdAt(line.getCreatedAt())
                .build();
    }

    private static String trimToNull(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        return value.trim();
    }

    private static String clientIp(HttpServletRequest request) {
        if (request == null) {
            return null;
        }
        String forwarded = request.getHeader("X-Forwarded-For");
        if (StringUtils.hasText(forwarded)) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }

    private static String userAgent(HttpServletRequest request) {
        return request == null ? null : request.getHeader("User-Agent");
    }

    public record AllocatedLine(
            UUID memberUserId, BigDecimal eligible, BigDecimal percentage, BigDecimal payout) {
        public AllocatedLine {
            Objects.requireNonNull(memberUserId);
            Objects.requireNonNull(eligible);
            Objects.requireNonNull(percentage);
            Objects.requireNonNull(payout);
        }
    }
}
