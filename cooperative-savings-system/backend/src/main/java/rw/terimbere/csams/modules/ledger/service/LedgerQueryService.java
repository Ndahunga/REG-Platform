package rw.terimbere.csams.modules.ledger.service;

import java.time.LocalDate;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import rw.terimbere.csams.modules.cooperative.repository.CooperativeRepository;
import rw.terimbere.csams.modules.ledger.dto.LedgerEntryResponse;
import rw.terimbere.csams.modules.ledger.entity.LedgerEntry;
import rw.terimbere.csams.modules.ledger.repository.LedgerEntryRepository;
import rw.terimbere.csams.security.CooperativeAuthorizationService;
import rw.terimbere.csams.shared.common.dto.PageResponse;
import rw.terimbere.csams.shared.exceptions.ResourceNotFoundException;
import rw.terimbere.csams.shared.financial.LedgerTransactionType;
import rw.terimbere.csams.shared.pagination.PageMapper;
import rw.terimbere.csams.shared.utilities.DateRangeValidator;
import rw.terimbere.csams.shared.utilities.MoneyUtils;

@Service
@RequiredArgsConstructor
public class LedgerQueryService {

    private final LedgerEntryRepository ledgerEntryRepository;
    private final CooperativeRepository cooperativeRepository;
    private final CooperativeAuthorizationService authorizationService;

    @Transactional(readOnly = true)
    public PageResponse<LedgerEntryResponse> list(
            UUID cooperativeId,
            LedgerTransactionType transactionType,
            LocalDate from,
            LocalDate to,
            UUID memberUserId,
            String sourceEntityType,
            Pageable pageable) {
        requireCooperative(cooperativeId);
        authorizationService.requireMembership(cooperativeId);
        DateRangeValidator.validateOptional(from, to);
        Page<LedgerEntry> page = ledgerEntryRepository.findFiltered(
                cooperativeId, transactionType, from, to, memberUserId, sourceEntityType, pageable);
        return PageMapper.toPageResponse(page, this::toResponse);
    }

    @Transactional(readOnly = true)
    public LedgerEntryResponse get(UUID cooperativeId, UUID entryId) {
        requireCooperative(cooperativeId);
        authorizationService.requireMembership(cooperativeId);
        LedgerEntry entry = ledgerEntryRepository
                .findByIdAndCooperativeId(entryId, cooperativeId)
                .orElseThrow(() -> new ResourceNotFoundException("LedgerEntry", entryId));
        return toResponse(entry);
    }

    private void requireCooperative(UUID cooperativeId) {
        cooperativeRepository
                .findByIdAndDeletedFalse(cooperativeId)
                .orElseThrow(() -> new ResourceNotFoundException("Cooperative", cooperativeId));
    }

    private LedgerEntryResponse toResponse(LedgerEntry e) {
        return LedgerEntryResponse.builder()
                .id(e.getId())
                .cooperativeId(e.getCooperativeId())
                .memberUserId(e.getMemberUserId())
                .transactionType(e.getTransactionType())
                .debitAmount(MoneyUtils.scale(e.getDebitAmount()))
                .creditAmount(MoneyUtils.scale(e.getCreditAmount()))
                .currency(e.getCurrency())
                .transactionDate(e.getTransactionDate())
                .reference(e.getReference())
                .sourceEntityType(e.getSourceEntityType())
                .sourceEntityId(e.getSourceEntityId())
                .description(e.getDescription())
                .status(e.getStatus())
                .recordedBy(e.getRecordedBy())
                .approvedBy(e.getApprovedBy())
                .reversesEntryId(e.getReversesEntryId())
                .idempotencyKey(e.getIdempotencyKey())
                .createdAt(e.getCreatedAt())
                .build();
    }
}
