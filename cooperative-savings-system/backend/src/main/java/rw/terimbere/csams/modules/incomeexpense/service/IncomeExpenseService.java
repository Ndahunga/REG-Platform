package rw.terimbere.csams.modules.incomeexpense.service;

import jakarta.servlet.http.HttpServletRequest;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
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
import rw.terimbere.csams.modules.incomeexpense.dto.IncomeExpenseCreateRequest;
import rw.terimbere.csams.modules.incomeexpense.dto.IncomeExpenseRejectRequest;
import rw.terimbere.csams.modules.incomeexpense.dto.IncomeExpenseResponse;
import rw.terimbere.csams.modules.incomeexpense.entity.IncomeExpenseApprovalStatus;
import rw.terimbere.csams.modules.incomeexpense.entity.IncomeExpenseCategory;
import rw.terimbere.csams.modules.incomeexpense.entity.IncomeExpenseTransaction;
import rw.terimbere.csams.modules.incomeexpense.entity.LedgerEffect;
import rw.terimbere.csams.modules.incomeexpense.repository.IncomeExpenseTransactionRepository;
import rw.terimbere.csams.modules.ledger.service.LedgerService;
import rw.terimbere.csams.security.CooperativeAuthorizationService;
import rw.terimbere.csams.security.CooperativeOfficerRoles;
import rw.terimbere.csams.security.UserPrincipal;
import rw.terimbere.csams.shared.auditing.AuditableAction;
import rw.terimbere.csams.shared.common.dto.PageResponse;
import rw.terimbere.csams.shared.exceptions.BusinessException;
import rw.terimbere.csams.shared.exceptions.ResourceNotFoundException;
import rw.terimbere.csams.shared.exceptions.ValidationException;
import rw.terimbere.csams.shared.financial.LedgerTransactionType;
import rw.terimbere.csams.shared.pagination.PageMapper;
import rw.terimbere.csams.shared.utilities.DateRangeValidator;
import rw.terimbere.csams.shared.utilities.MoneyUtils;

@Service
@RequiredArgsConstructor
public class IncomeExpenseService {

    private final IncomeExpenseTransactionRepository transactionRepository;
    private final CooperativeRepository cooperativeRepository;
    private final LedgerService ledgerService;
    private final CooperativeAuthorizationService authorizationService;
    private final AuditService auditService;

    @Transactional(readOnly = true)
    public PageResponse<IncomeExpenseResponse> list(
            UUID cooperativeId,
            IncomeExpenseCategory category,
            IncomeExpenseApprovalStatus status,
            LocalDate from,
            LocalDate to,
            Pageable pageable) {
        requireCooperative(cooperativeId);
        authorizationService.requireMembership(cooperativeId);
        DateRangeValidator.validateOptional(from, to);
        Page<IncomeExpenseTransaction> page = transactionRepository.findFiltered(
                cooperativeId, category, status, from, to, pageable);
        return PageMapper.toPageResponse(page, this::toResponse);
    }

    @Transactional(readOnly = true)
    public IncomeExpenseResponse get(UUID cooperativeId, UUID transactionId) {
        requireCooperative(cooperativeId);
        authorizationService.requireMembership(cooperativeId);
        return toResponse(requireTransaction(cooperativeId, transactionId));
    }

    @Transactional
    public IncomeExpenseResponse create(
            UUID cooperativeId, IncomeExpenseCreateRequest request, HttpServletRequest httpRequest) {
        requireCooperative(cooperativeId);
        UserPrincipal principal = authorizationService.currentPrincipal();
        authorizationService.requireMembership(cooperativeId);

        validateCategoryAndEffect(request.getCategory(), request.getLedgerEffect());

        BigDecimal amount = MoneyUtils.scaleForStorage(request.getAmount());
        MoneyUtils.assertPositive(amount);

        IncomeExpenseTransaction tx = IncomeExpenseTransaction.builder()
                .cooperativeId(cooperativeId)
                .category(request.getCategory())
                .amount(amount)
                .ledgerEffect(
                        request.getCategory() == IncomeExpenseCategory.ADJUSTMENT
                                ? request.getLedgerEffect()
                                : null)
                .transactionDate(request.getTransactionDate())
                .reference(trimToNull(request.getReference()))
                .description(trimToNull(request.getDescription()))
                .notes(trimToNull(request.getNotes()))
                .supportingFileKey(trimToNull(request.getSupportingFileKey()))
                .approvalStatus(IncomeExpenseApprovalStatus.PENDING)
                .recordedBy(principal.getId())
                .build();
        tx = transactionRepository.save(tx);

        auditService.record(
                principal.getId(),
                cooperativeId,
                AuditableAction.INCOME_EXPENSE_CREATE,
                "IncomeExpenseTransaction",
                tx.getId(),
                null,
                "{\"category\":\""
                        + tx.getCategory()
                        + "\",\"amount\":\""
                        + amount
                        + "\",\"status\":\"PENDING\"}",
                clientIp(httpRequest),
                userAgent(httpRequest));
        return toResponse(tx);
    }

    @Transactional
    public IncomeExpenseResponse approve(
            UUID cooperativeId, UUID transactionId, HttpServletRequest httpRequest) {
        Cooperative cooperative = requireCooperative(cooperativeId);
        UserPrincipal principal = authorizationService.currentPrincipal();
        authorizationService.requireMembership(cooperativeId);
        CooperativeOfficerRoles.requireFundAuthorize(principal);
        IncomeExpenseTransaction tx = requireTransaction(cooperativeId, transactionId);

        if (tx.getApprovalStatus() != IncomeExpenseApprovalStatus.PENDING) {
            throw new BusinessException("Only PENDING transactions can be approved");
        }

        tx.setApprovalStatus(IncomeExpenseApprovalStatus.APPROVED);
        tx.setApprovedBy(principal.getId());
        tx.setApprovedAt(Instant.now());
        tx = transactionRepository.save(tx);

        postLedger(cooperative, tx, principal.getId());

        auditService.record(
                principal.getId(),
                cooperativeId,
                AuditableAction.INCOME_EXPENSE_APPROVE,
                "IncomeExpenseTransaction",
                tx.getId(),
                "{\"status\":\"PENDING\"}",
                "{\"status\":\"APPROVED\"}",
                clientIp(httpRequest),
                userAgent(httpRequest));
        return toResponse(tx);
    }

    @Transactional
    public IncomeExpenseResponse reject(
            UUID cooperativeId,
            UUID transactionId,
            IncomeExpenseRejectRequest request,
            HttpServletRequest httpRequest) {
        requireCooperative(cooperativeId);
        UserPrincipal principal = authorizationService.currentPrincipal();
        authorizationService.requireMembership(cooperativeId);
        IncomeExpenseTransaction tx = requireTransaction(cooperativeId, transactionId);

        if (tx.getApprovalStatus() != IncomeExpenseApprovalStatus.PENDING) {
            throw new BusinessException("Only PENDING transactions can be rejected");
        }
        if (request == null || !StringUtils.hasText(request.getRejectionReason())) {
            throw new ValidationException("Rejection reason is required");
        }

        tx.setApprovalStatus(IncomeExpenseApprovalStatus.REJECTED);
        tx.setApprovedBy(principal.getId());
        tx.setApprovedAt(Instant.now());
        tx.setRejectionReason(request.getRejectionReason().trim());
        tx = transactionRepository.save(tx);

        auditService.record(
                principal.getId(),
                cooperativeId,
                AuditableAction.INCOME_EXPENSE_REJECT,
                "IncomeExpenseTransaction",
                tx.getId(),
                "{\"status\":\"PENDING\"}",
                "{\"status\":\"REJECTED\"}",
                clientIp(httpRequest),
                userAgent(httpRequest));
        return toResponse(tx);
    }

    private void postLedger(Cooperative cooperative, IncomeExpenseTransaction tx, UUID actorId) {
        LedgerTransactionType type;
        BigDecimal debit = BigDecimal.ZERO;
        BigDecimal credit = BigDecimal.ZERO;

        switch (tx.getCategory()) {
            case OTHER_INCOME -> {
                type = LedgerTransactionType.OTHER_INCOME;
                credit = tx.getAmount();
            }
            case GENERAL_EXPENSE -> {
                type = LedgerTransactionType.GENERAL_EXPENSE;
                debit = tx.getAmount();
            }
            case INTEREST_EXPENSE -> {
                type = LedgerTransactionType.INTEREST_EXPENSE;
                debit = tx.getAmount();
            }
            case ADJUSTMENT -> {
                type = LedgerTransactionType.ADJUSTMENT;
                if (tx.getLedgerEffect() == LedgerEffect.CREDIT) {
                    credit = tx.getAmount();
                } else if (tx.getLedgerEffect() == LedgerEffect.DEBIT) {
                    debit = tx.getAmount();
                } else {
                    throw new BusinessException("ADJUSTMENT requires ledgerEffect CREDIT or DEBIT");
                }
            }
            default -> throw new BusinessException("Unsupported category: " + tx.getCategory());
        }

        ledgerService.appendApproved(LedgerService.AppendRequest.builder()
                .cooperativeId(tx.getCooperativeId())
                .transactionType(type)
                .debitAmount(debit)
                .creditAmount(credit)
                .currency(cooperative.getCurrency())
                .transactionDate(tx.getTransactionDate())
                .reference(tx.getReference() != null ? tx.getReference() : "TX-" + tx.getId())
                .sourceEntityType(LedgerService.SOURCE_INCOME_EXPENSE)
                .sourceEntityId(tx.getId())
                .description(tx.getDescription() != null ? tx.getDescription() : tx.getCategory().name())
                .recordedBy(actorId)
                .approvedBy(actorId)
                .idempotencyKey(LedgerService.incomeExpenseKey(tx.getId(), type))
                .build());
    }

    private static void validateCategoryAndEffect(IncomeExpenseCategory category, LedgerEffect effect) {
        if (category == IncomeExpenseCategory.ADJUSTMENT) {
            if (effect == null) {
                throw new ValidationException("ledgerEffect is required when category is ADJUSTMENT");
            }
        } else if (effect != null) {
            throw new ValidationException("ledgerEffect is only allowed when category is ADJUSTMENT");
        }
    }

    private Cooperative requireCooperative(UUID cooperativeId) {
        return cooperativeRepository
                .findByIdAndDeletedFalse(cooperativeId)
                .orElseThrow(() -> new ResourceNotFoundException("Cooperative", cooperativeId));
    }

    private IncomeExpenseTransaction requireTransaction(UUID cooperativeId, UUID transactionId) {
        return transactionRepository
                .findByIdAndCooperativeId(transactionId, cooperativeId)
                .orElseThrow(() -> new ResourceNotFoundException("IncomeExpenseTransaction", transactionId));
    }

    private IncomeExpenseResponse toResponse(IncomeExpenseTransaction tx) {
        return IncomeExpenseResponse.builder()
                .id(tx.getId())
                .cooperativeId(tx.getCooperativeId())
                .category(tx.getCategory())
                .amount(MoneyUtils.scale(tx.getAmount()))
                .ledgerEffect(tx.getLedgerEffect())
                .transactionDate(tx.getTransactionDate())
                .reference(tx.getReference())
                .description(tx.getDescription())
                .notes(tx.getNotes())
                .supportingFileKey(tx.getSupportingFileKey())
                .approvalStatus(tx.getApprovalStatus())
                .recordedBy(tx.getRecordedBy())
                .approvedBy(tx.getApprovedBy())
                .approvedAt(tx.getApprovedAt())
                .rejectionReason(tx.getRejectionReason())
                .createdAt(tx.getCreatedAt())
                .updatedAt(tx.getUpdatedAt())
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
}
