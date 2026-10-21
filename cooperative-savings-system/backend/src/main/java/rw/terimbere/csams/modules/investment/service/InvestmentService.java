package rw.terimbere.csams.modules.investment.service;

import jakarta.servlet.http.HttpServletRequest;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import rw.terimbere.csams.modules.audit.service.AuditService;
import rw.terimbere.csams.modules.cooperative.entity.Cooperative;
import rw.terimbere.csams.modules.cooperative.repository.CooperativeRepository;
import rw.terimbere.csams.modules.investment.dto.InvestmentCreateRequest;
import rw.terimbere.csams.modules.investment.dto.InvestmentLossRequest;
import rw.terimbere.csams.modules.investment.dto.InvestmentResponse;
import rw.terimbere.csams.modules.investment.dto.InvestmentReturnCreateRequest;
import rw.terimbere.csams.modules.investment.dto.InvestmentReturnResponse;
import rw.terimbere.csams.modules.investment.entity.Investment;
import rw.terimbere.csams.modules.investment.entity.InvestmentReturn;
import rw.terimbere.csams.modules.investment.entity.InvestmentStatus;
import rw.terimbere.csams.modules.investment.repository.InvestmentRepository;
import rw.terimbere.csams.modules.investment.repository.InvestmentReturnRepository;
import rw.terimbere.csams.modules.cooperative.service.SchemeOperatingModelGuard;
import rw.terimbere.csams.modules.fundlock.SchemeFundLockService;
import rw.terimbere.csams.modules.ledger.service.LedgerService;
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
public class InvestmentService {

    private final InvestmentRepository investmentRepository;
    private final InvestmentReturnRepository investmentReturnRepository;
    private final CooperativeRepository cooperativeRepository;
    private final LedgerService ledgerService;
    private final SchemeFundLockService schemeFundLockService;
    private final SchemeOperatingModelGuard operatingModelGuard;
    private final LedgerFinancialCalculationService financialCalculationService;
    private final CooperativeAuthorizationService authorizationService;
    private final AuditService auditService;

    @Transactional(readOnly = true)
    public PageResponse<InvestmentResponse> list(
            UUID cooperativeId, InvestmentStatus status, Pageable pageable) {
        requireCooperative(cooperativeId);
        authorizationService.requireMembership(cooperativeId);
        Page<Investment> page = status == null
                ? investmentRepository.findByCooperativeId(cooperativeId, pageable)
                : investmentRepository.findByCooperativeIdAndStatus(cooperativeId, status, pageable);
        return PageMapper.toPageResponse(page, this::toResponse);
    }

    @Transactional(readOnly = true)
    public InvestmentResponse get(UUID cooperativeId, UUID investmentId) {
        requireCooperative(cooperativeId);
        authorizationService.requireMembership(cooperativeId);
        return toResponse(requireInvestment(cooperativeId, investmentId));
    }

    @Transactional
    public InvestmentResponse create(
            UUID cooperativeId, InvestmentCreateRequest request, HttpServletRequest httpRequest) {
        requireCooperative(cooperativeId);
        UserPrincipal principal = authorizationService.currentPrincipal();
        authorizationService.requireMembership(cooperativeId);

        BigDecimal amount = MoneyUtils.scaleForStorage(request.getAmount());
        MoneyUtils.assertPositive(amount);

        Investment investment = Investment.builder()
                .cooperativeId(cooperativeId)
                .name(request.getName().trim())
                .description(trimToNull(request.getDescription()))
                .amount(amount)
                .expectedReturnAmount(
                        request.getExpectedReturnAmount() == null
                                ? null
                                : MoneyUtils.scaleForStorage(request.getExpectedReturnAmount()))
                .expectedReturnDate(request.getExpectedReturnDate())
                .remainingCapital(BigDecimal.ZERO)
                .totalCapitalReturned(BigDecimal.ZERO)
                .totalProfitReturned(BigDecimal.ZERO)
                .status(InvestmentStatus.PLANNED)
                .documentFileKey(trimToNull(request.getDocumentFileKey()))
                .createdBy(principal.getId())
                .build();
        investment = investmentRepository.save(investment);

        auditService.record(
                principal.getId(),
                cooperativeId,
                AuditableAction.INVESTMENT_CREATE,
                "Investment",
                investment.getId(),
                null,
                "{\"status\":\"PLANNED\",\"amount\":\"" + amount + "\"}",
                clientIp(httpRequest),
                userAgent(httpRequest));
        return toResponse(investment);
    }

    @Transactional
    public InvestmentResponse activate(UUID cooperativeId, UUID investmentId, HttpServletRequest httpRequest) {
        Cooperative cooperative = requireCooperative(cooperativeId);
        UserPrincipal principal = authorizationService.currentPrincipal();
        authorizationService.requireMembership(cooperativeId);
        CooperativeOfficerRoles.requireFundAuthorize(principal);
        // Serialize with every other operation that spends this scheme's funds, before the investment is read for
        // its final decision and before available funds are checked.
        schemeFundLockService.lockSchemeFund(cooperativeId);
        operatingModelGuard.requireAccumulatingWorkflows(cooperativeId, "Investment activation");
        Investment investment = requireInvestment(cooperativeId, investmentId);

        if (investment.getStatus() != InvestmentStatus.PLANNED) {
            throw new BusinessException("Only PLANNED investments can be activated");
        }

        BigDecimal amount = MoneyUtils.scaleForStorage(investment.getAmount());
        MoneyUtils.assertPositive(amount);

        BigDecimal available = financialCalculationService.calculateAvailableGroupFund(cooperativeId);
        if (available.compareTo(MoneyUtils.scale(amount)) < 0) {
            throw new BusinessException(
                    "INSUFFICIENT_GROUP_FUND",
                    "Available group fund is insufficient for investment activation. Available: "
                            + available
                            + ", required: "
                            + MoneyUtils.scale(amount));
        }

        Instant now = Instant.now();
        investment.setStatus(InvestmentStatus.ACTIVE);
        investment.setRemainingCapital(amount);
        investment.setActivatedAt(now);
        investment = investmentRepository.save(investment);

        ledgerService.appendApproved(LedgerService.AppendRequest.builder()
                .cooperativeId(cooperativeId)
                .transactionType(LedgerTransactionType.INVESTMENT_OUTFLOW)
                .debitAmount(amount)
                .creditAmount(BigDecimal.ZERO)
                .currency(cooperative.getCurrency())
                .transactionDate(java.time.LocalDate.now())
                .reference("INV-" + investment.getId())
                .sourceEntityType(LedgerService.SOURCE_INVESTMENT)
                .sourceEntityId(investment.getId())
                .description("Investment activation: " + investment.getName())
                .recordedBy(principal.getId())
                .approvedBy(principal.getId())
                .idempotencyKey(LedgerService.investmentOutflowKey(investment.getId()))
                .build());

        auditService.record(
                principal.getId(),
                cooperativeId,
                AuditableAction.INVESTMENT_ACTIVATE,
                "Investment",
                investment.getId(),
                "{\"status\":\"PLANNED\"}",
                "{\"status\":\"ACTIVE\",\"remainingCapital\":\"" + amount + "\"}",
                clientIp(httpRequest),
                userAgent(httpRequest));
        return toResponse(investment);
    }

    @Transactional
    public InvestmentResponse cancel(UUID cooperativeId, UUID investmentId, HttpServletRequest httpRequest) {
        requireCooperative(cooperativeId);
        UserPrincipal principal = authorizationService.currentPrincipal();
        authorizationService.requireMembership(cooperativeId);
        Investment investment = requireInvestment(cooperativeId, investmentId);

        if (investment.getStatus() != InvestmentStatus.PLANNED) {
            throw new BusinessException(
                    "Only PLANNED investments can be cancelled. Use record-loss for active capital write-down.");
        }

        investment.setStatus(InvestmentStatus.CANCELLED);
        investment = investmentRepository.save(investment);

        auditService.record(
                principal.getId(),
                cooperativeId,
                AuditableAction.INVESTMENT_CANCEL,
                "Investment",
                investment.getId(),
                "{\"status\":\"PLANNED\"}",
                "{\"status\":\"CANCELLED\"}",
                clientIp(httpRequest),
                userAgent(httpRequest));
        return toResponse(investment);
    }

    @Transactional
    public InvestmentReturnResponse recordReturn(
            UUID cooperativeId,
            UUID investmentId,
            InvestmentReturnCreateRequest request,
            HttpServletRequest httpRequest) {
        Cooperative cooperative = requireCooperative(cooperativeId);
        UserPrincipal principal = authorizationService.currentPrincipal();
        authorizationService.requireMembership(cooperativeId);
        CooperativeOfficerRoles.requireFundAuthorize(principal);
        Investment investment = requireInvestment(cooperativeId, investmentId);

        if (investment.getStatus() != InvestmentStatus.ACTIVE
                && investment.getStatus() != InvestmentStatus.PARTIALLY_RETURNED) {
            throw new BusinessException("Returns can only be recorded for ACTIVE or PARTIALLY_RETURNED investments");
        }

        BigDecimal capitalPortion = MoneyUtils.scaleForStorage(
                request.getCapitalPortion() == null ? BigDecimal.ZERO : request.getCapitalPortion());
        BigDecimal profitPortion = MoneyUtils.scaleForStorage(
                request.getProfitPortion() == null ? BigDecimal.ZERO : request.getProfitPortion());
        MoneyUtils.assertNonNegative(capitalPortion);
        MoneyUtils.assertNonNegative(profitPortion);
        BigDecimal total = MoneyUtils.scaleForStorage(capitalPortion.add(profitPortion));
        if (MoneyUtils.isZero(total)) {
            throw new ValidationException("At least one of capitalPortion or profitPortion must be positive");
        }

        BigDecimal remaining = MoneyUtils.scaleForStorage(investment.getRemainingCapital());
        if (capitalPortion.compareTo(remaining) > 0) {
            throw new BusinessException(
                    "Capital portion exceeds remaining capital. Remaining: "
                            + MoneyUtils.scale(remaining)
                            + ", requested: "
                            + MoneyUtils.scale(capitalPortion));
        }

        InvestmentReturn investmentReturn = InvestmentReturn.builder()
                .investmentId(investment.getId())
                .cooperativeId(cooperativeId)
                .returnDate(request.getReturnDate())
                .capitalPortion(capitalPortion)
                .profitPortion(profitPortion)
                .amountTotal(total)
                .notes(trimToNull(request.getNotes()))
                .reference(trimToNull(request.getReference()))
                .recordedBy(principal.getId())
                .build();
        investmentReturn = investmentReturnRepository.save(investmentReturn);

        if (!MoneyUtils.isZero(capitalPortion)) {
            ledgerService.appendApproved(LedgerService.AppendRequest.builder()
                    .cooperativeId(cooperativeId)
                    .transactionType(LedgerTransactionType.INVESTMENT_CAPITAL_RETURN)
                    .debitAmount(BigDecimal.ZERO)
                    .creditAmount(capitalPortion)
                    .currency(cooperative.getCurrency())
                    .transactionDate(request.getReturnDate())
                    .reference(trimToNull(request.getReference()) != null
                            ? request.getReference().trim()
                            : "INV-RET-" + investmentReturn.getId())
                    .sourceEntityType(LedgerService.SOURCE_INVESTMENT_RETURN)
                    .sourceEntityId(investmentReturn.getId())
                    .description("Investment capital return: " + investment.getName())
                    .recordedBy(principal.getId())
                    .approvedBy(principal.getId())
                    .idempotencyKey(LedgerService.investmentCapitalReturnKey(investmentReturn.getId()))
                    .build());

            investment.setRemainingCapital(MoneyUtils.scaleForStorage(remaining.subtract(capitalPortion)));
            investment.setTotalCapitalReturned(MoneyUtils.scaleForStorage(
                    investment.getTotalCapitalReturned().add(capitalPortion)));
        }

        if (!MoneyUtils.isZero(profitPortion)) {
            ledgerService.appendApproved(LedgerService.AppendRequest.builder()
                    .cooperativeId(cooperativeId)
                    .transactionType(LedgerTransactionType.INVESTMENT_PROFIT)
                    .debitAmount(BigDecimal.ZERO)
                    .creditAmount(profitPortion)
                    .currency(cooperative.getCurrency())
                    .transactionDate(request.getReturnDate())
                    .reference(trimToNull(request.getReference()) != null
                            ? request.getReference().trim()
                            : "INV-PROFIT-" + investmentReturn.getId())
                    .sourceEntityType(LedgerService.SOURCE_INVESTMENT_RETURN)
                    .sourceEntityId(investmentReturn.getId())
                    .description("Investment profit: " + investment.getName())
                    .recordedBy(principal.getId())
                    .approvedBy(principal.getId())
                    .idempotencyKey(LedgerService.investmentProfitKey(investmentReturn.getId()))
                    .build());

            investment.setTotalProfitReturned(MoneyUtils.scaleForStorage(
                    investment.getTotalProfitReturned().add(profitPortion)));
        }

        if (MoneyUtils.isZero(investment.getRemainingCapital())) {
            investment.setStatus(InvestmentStatus.COMPLETED);
            investment.setCompletedAt(Instant.now());
        } else {
            investment.setStatus(InvestmentStatus.PARTIALLY_RETURNED);
        }
        investmentRepository.save(investment);

        auditService.record(
                principal.getId(),
                cooperativeId,
                AuditableAction.INVESTMENT_RETURN,
                "InvestmentReturn",
                investmentReturn.getId(),
                null,
                "{\"investmentId\":\""
                        + investmentId
                        + "\",\"capital\":\""
                        + capitalPortion
                        + "\",\"profit\":\""
                        + profitPortion
                        + "\"}",
                clientIp(httpRequest),
                userAgent(httpRequest));
        return toReturnResponse(investmentReturn);
    }

    @Transactional
    public InvestmentResponse recordLoss(
            UUID cooperativeId,
            UUID investmentId,
            InvestmentLossRequest request,
            HttpServletRequest httpRequest) {
        requireCooperative(cooperativeId);
        UserPrincipal principal = authorizationService.currentPrincipal();
        authorizationService.requireMembership(cooperativeId);
        CooperativeOfficerRoles.requireFundAuthorize(principal);
        Investment investment = requireInvestment(cooperativeId, investmentId);

        if (investment.getStatus() != InvestmentStatus.ACTIVE
                && investment.getStatus() != InvestmentStatus.PARTIALLY_RETURNED) {
            throw new BusinessException(
                    "Loss can only be recorded for ACTIVE or PARTIALLY_RETURNED investments");
        }

        BigDecimal remaining = MoneyUtils.scaleForStorage(investment.getRemainingCapital());
        if (MoneyUtils.isZero(remaining)) {
            throw new BusinessException("No remaining capital to write off as loss");
        }

        // Zero remaining without posting INVESTMENT_CAPITAL_RETURN — capital is lost to the group fund.
        String priorNotes = investment.getDescription();
        String lossNote = "LOSS_RECORDED remaining capital "
                + MoneyUtils.scale(remaining)
                + (request != null && StringUtils.hasText(request.getNotes())
                        ? ": " + request.getNotes().trim()
                        : "");
        investment.setDescription(
                priorNotes == null ? lossNote : priorNotes + " | " + lossNote);
        investment.setRemainingCapital(BigDecimal.ZERO.setScale(MoneyUtils.STORAGE_SCALE));
        investment.setStatus(InvestmentStatus.LOSS_RECORDED);
        investment.setCompletedAt(Instant.now());
        investment = investmentRepository.save(investment);

        auditService.record(
                principal.getId(),
                cooperativeId,
                AuditableAction.INVESTMENT_LOSS,
                "Investment",
                investment.getId(),
                "{\"remainingCapital\":\"" + remaining + "\"}",
                "{\"status\":\"LOSS_RECORDED\",\"writtenOff\":\"" + remaining + "\"}",
                clientIp(httpRequest),
                userAgent(httpRequest));
        return toResponse(investment);
    }

    @Transactional(readOnly = true)
    public List<InvestmentReturnResponse> listReturns(UUID cooperativeId, UUID investmentId) {
        requireCooperative(cooperativeId);
        authorizationService.requireMembership(cooperativeId);
        requireInvestment(cooperativeId, investmentId);
        return investmentReturnRepository
                .findByInvestmentIdAndCooperativeIdOrderByReturnDateDescCreatedAtDesc(investmentId, cooperativeId)
                .stream()
                .map(this::toReturnResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public long countActiveInvestments(UUID cooperativeId) {
        return investmentRepository.countByCooperativeIdAndStatusIn(
                cooperativeId, EnumSet.of(InvestmentStatus.ACTIVE, InvestmentStatus.PARTIALLY_RETURNED));
    }

    private Cooperative requireCooperative(UUID cooperativeId) {
        return cooperativeRepository
                .findByIdAndDeletedFalse(cooperativeId)
                .orElseThrow(() -> new ResourceNotFoundException("Cooperative", cooperativeId));
    }

    private Investment requireInvestment(UUID cooperativeId, UUID investmentId) {
        return investmentRepository
                .findByIdAndCooperativeId(investmentId, cooperativeId)
                .orElseThrow(() -> new ResourceNotFoundException("Investment", investmentId));
    }

    private InvestmentResponse toResponse(Investment investment) {
        return InvestmentResponse.builder()
                .id(investment.getId())
                .cooperativeId(investment.getCooperativeId())
                .name(investment.getName())
                .description(investment.getDescription())
                .amount(MoneyUtils.scale(investment.getAmount()))
                .expectedReturnAmount(scaleOrNull(investment.getExpectedReturnAmount()))
                .expectedReturnDate(investment.getExpectedReturnDate())
                .remainingCapital(MoneyUtils.scale(investment.getRemainingCapital()))
                .totalCapitalReturned(MoneyUtils.scale(investment.getTotalCapitalReturned()))
                .totalProfitReturned(MoneyUtils.scale(investment.getTotalProfitReturned()))
                .status(investment.getStatus())
                .documentFileKey(investment.getDocumentFileKey())
                .activatedAt(investment.getActivatedAt())
                .completedAt(investment.getCompletedAt())
                .createdBy(investment.getCreatedBy())
                .createdAt(investment.getCreatedAt())
                .updatedAt(investment.getUpdatedAt())
                .build();
    }

    private InvestmentReturnResponse toReturnResponse(InvestmentReturn r) {
        return InvestmentReturnResponse.builder()
                .id(r.getId())
                .investmentId(r.getInvestmentId())
                .cooperativeId(r.getCooperativeId())
                .returnDate(r.getReturnDate())
                .capitalPortion(MoneyUtils.scale(r.getCapitalPortion()))
                .profitPortion(MoneyUtils.scale(r.getProfitPortion()))
                .amountTotal(MoneyUtils.scale(r.getAmountTotal()))
                .notes(r.getNotes())
                .reference(r.getReference())
                .recordedBy(r.getRecordedBy())
                .createdAt(r.getCreatedAt())
                .build();
    }

    private static BigDecimal scaleOrNull(BigDecimal value) {
        return value == null ? null : MoneyUtils.scale(value);
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
}
