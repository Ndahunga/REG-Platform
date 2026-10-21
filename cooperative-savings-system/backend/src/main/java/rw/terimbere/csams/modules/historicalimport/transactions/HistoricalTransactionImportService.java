package rw.terimbere.csams.modules.historicalimport.transactions;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.math.BigDecimal;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;
import rw.terimbere.csams.modules.audit.service.AuditService;
import rw.terimbere.csams.modules.contribution.entity.Contribution;
import rw.terimbere.csams.modules.contribution.entity.ContributionStatus;
import rw.terimbere.csams.modules.contribution.obligation.ContributionDueDates;
import rw.terimbere.csams.modules.contribution.repository.ContributionRepository;
import rw.terimbere.csams.modules.cooperative.entity.Cooperative;
import rw.terimbere.csams.modules.cooperative.repository.CooperativeRepository;
import rw.terimbere.csams.modules.fine.entity.Fine;
import rw.terimbere.csams.modules.fine.entity.FineCalculationMode;
import rw.terimbere.csams.modules.fine.entity.FinePayment;
import rw.terimbere.csams.modules.fine.entity.FinePaymentMethod;
import rw.terimbere.csams.modules.fine.entity.FinePaymentStatus;
import rw.terimbere.csams.modules.fine.entity.FineStatus;
import rw.terimbere.csams.modules.fine.entity.FineType;
import rw.terimbere.csams.modules.fine.repository.FinePaymentRepository;
import rw.terimbere.csams.modules.fine.repository.FineRepository;
import rw.terimbere.csams.modules.fundlock.SchemeFundLockService;
import rw.terimbere.csams.modules.incomeexpense.entity.IncomeExpenseApprovalStatus;
import rw.terimbere.csams.modules.incomeexpense.entity.IncomeExpenseCategory;
import rw.terimbere.csams.modules.incomeexpense.entity.IncomeExpenseTransaction;
import rw.terimbere.csams.modules.incomeexpense.repository.IncomeExpenseTransactionRepository;
import rw.terimbere.csams.modules.historicalimport.transactions.HistoricalTxValidator.Checked;
import rw.terimbere.csams.modules.historicalimport.transactions.HistoricalTxValidator.Context;
import rw.terimbere.csams.modules.historicalimport.transactions.HistoricalTxValidator.Issue;
import rw.terimbere.csams.modules.historicalimport.transactions.HistoricalTxValidator.Result;
import rw.terimbere.csams.modules.historicalimport.transactions.HistoricalTxWorkbook.RawRow;
import rw.terimbere.csams.modules.historicalimport.strategy.HistoricalMigrationGuard;
import rw.terimbere.csams.modules.historicalimport.strategy.MigrationStrategy;
import rw.terimbere.csams.modules.ledger.entity.LedgerEntry;
import rw.terimbere.csams.modules.ledger.repository.LedgerEntryRepository;
import rw.terimbere.csams.modules.ledger.service.LedgerService;
import rw.terimbere.csams.modules.membership.entity.CooperativeMembership;
import rw.terimbere.csams.modules.membership.repository.CooperativeMembershipRepository;
import rw.terimbere.csams.modules.user.entity.User;
import rw.terimbere.csams.modules.user.repository.UserRepository;
import rw.terimbere.csams.security.CooperativeAuthorizationService;
import rw.terimbere.csams.security.CooperativeOfficerRoles;
import rw.terimbere.csams.security.UserPrincipal;
import rw.terimbere.csams.shared.auditing.AuditableAction;
import rw.terimbere.csams.shared.exceptions.BusinessException;
import rw.terimbere.csams.shared.exceptions.ConflictException;
import rw.terimbere.csams.shared.exceptions.ForbiddenException;
import rw.terimbere.csams.shared.exceptions.ResourceNotFoundException;
import rw.terimbere.csams.shared.exceptions.ValidationException;
import rw.terimbere.csams.shared.utilities.MoneyUtils;

/**
 * Guided import of a scheme's multi-year history.
 *
 * <p>The life of a batch: <b>upload</b> (validated and stored, a dry run that posts nothing, idempotent per file) then
 * <b>approval</b> by a different officer with fund authority, then <b>posting</b> in one transaction, then final.
 * Cancelling is possible only before posting. Nothing posted is edited or deleted.
 *
 * <p>What posting does: each valid row becomes one ledger entry dated at the original date, carrying the original
 * reference, together with the authoritative operational record where one can be built without inventing anything: a
 * CONTRIBUTION becomes a historical contribution obligation, a FINE_PAYMENT a fully paid fine with its approved payment,
 * and OTHER_INCOME / GENERAL_EXPENSE an approved income/expense transaction. No obligation snapshot, rotation period or
 * recipient is invented, so no historical amount can become verified rotating payout eligibility.
 *
 * <p><b>Not importable:</b> loan events and member payouts are rejected at validation (see {@link HistoricalTxType}): a
 * loan record needs rate, term and schedule, and a payout needs a rotation period and recipient, none of which the sheet
 * carries. Nothing is ever posted to the ledger alone. Batches posted by an earlier version that did include such rows
 * are left untouched. Opening and closing balances are reconciled and a variance must be acknowledged, in writing, by the
 * approver.
 */
@Service
@RequiredArgsConstructor
public class HistoricalTransactionImportService {

    public static final String LEDGER_SOURCE = "HISTORICAL_IMPORT";
    public static final String KEY_PREFIX = "HISTIMP:";
    private static final long MAX_BYTES = 5L * 1024 * 1024;
    private static final String CONTRIBUTION_WRITE = "CONTRIBUTION_WRITE";
    private static final String FUND_AUTHORIZE = "FUND_AUTHORIZE";

    private final HistoricalLedgerImportRepository importRepository;
    private final HistoricalLedgerRowRepository rowRepository;
    private final HistoricalLedgerEventRepository eventRepository;
    private final CooperativeRepository cooperativeRepository;
    private final CooperativeMembershipRepository membershipRepository;
    private final UserRepository userRepository;
    private final ContributionRepository contributionRepository;
    private final LedgerEntryRepository ledgerEntryRepository;
    private final FineRepository fineRepository;
    private final FinePaymentRepository finePaymentRepository;
    private final IncomeExpenseTransactionRepository incomeExpenseRepository;
    private final LedgerService ledgerService;
    private final SchemeFundLockService fundLockService;
    private final CooperativeAuthorizationService authorizationService;
    private final AuditService auditService;
    private final TransactionTemplate transactionTemplate;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final HistoricalMigrationGuard migrationGuard;

    // ------------------------------------------------------------------ template

    public byte[] template(UUID cooperativeId) {
        requireUploader(cooperativeId);
        return HistoricalTxWorkbook.template();
    }

    // ------------------------------------------------------------------ upload and dry run

    /** Validates the workbook and stores the result. Posts nothing. Uploading the same file again returns the same batch. */
    public BatchView preview(UUID cooperativeId, MultipartFile file) {
        UserPrincipal actor = requireUploader(cooperativeId);
        byte[] bytes = readFile(file);
        String sha = sha256(bytes);
        HistoricalLedgerImport existing = importRepository.findByCooperativeIdAndActiveKey(cooperativeId, sha).orElse(null);
        if (existing != null) {
            return view(existing, true);
        }
        HistoricalTxWorkbook.Parsed parsed = HistoricalTxWorkbook.parse(bytes);
        Cooperative cooperative = cooperativeRepository
                .findByIdAndDeletedFalse(cooperativeId)
                .orElseThrow(() -> new ResourceNotFoundException("Cooperative", cooperativeId));
        Result result = HistoricalTxValidator.validate(parsed.members(), parsed.transactions(), context(cooperative));
        String name = fileName(file);
        try {
            HistoricalLedgerImport saved = transactionTemplate.execute(status -> persist(cooperative, actor, name, sha, result));
            return view(saved, false);
        } catch (DataIntegrityViolationException race) {
            // the same file was stored by a concurrent request: that batch is the answer
            return importRepository.findByCooperativeIdAndActiveKey(cooperativeId, sha)
                    .map(i -> view(i, true))
                    .orElseThrow(() -> race);
        }
    }

    private HistoricalLedgerImport persist(Cooperative cooperative, UserPrincipal actor, String fileName, String sha, Result result) {
        Instant now = Instant.now(clock);
        var recon = result.reconciliation();
        HistoricalLedgerImport imp = importRepository.saveAndFlush(HistoricalLedgerImport.builder()
                .cooperativeId(cooperative.getId())
                .fileName(fileName)
                .fileSha256(sha)
                .activeKey(sha)
                .status(HistoricalBatchStatus.PREVIEWED)
                .currency(cooperative.getCurrency())
                .historyEndDate(recon.historyEnd())
                .openingBalance(recon.opening())
                .declaredClosingBalance(recon.declaredClosing())
                .computedClosingBalance(recon.computedClosing())
                .balanceVariance(recon.variance())
                .totalRows(result.members().size() + result.transactions().size())
                .errorRows(result.errorRows())
                .validRows(result.members().size() + result.transactions().size() - result.errorRows())
                .errorCount(result.errorCount())
                .summary(summaryJson(result))
                .uploadedBy(actor.getId())
                .uploadedAt(now)
                .build());
        List<HistoricalLedgerRow> rows = new ArrayList<>();
        for (Checked c : result.members()) {
            rows.add(row(imp, "MEMBERS", c));
        }
        for (Checked c : result.transactions()) {
            rows.add(row(imp, "TRANSACTIONS", c));
        }
        rowRepository.saveAll(rows);
        event(imp, actor, null, HistoricalBatchStatus.PREVIEWED,
                imp.getErrorCount() == 0 ? "Validated: ready for approval" : "Validated with " + imp.getErrorCount() + " error(s)");
        audit(actor, imp, AuditableAction.HISTORICAL_IMPORT_PREVIEW, "{\"rows\":" + imp.getTotalRows() + ",\"errors\":" + imp.getErrorCount() + "}");
        return imp;
    }

    // ------------------------------------------------------------------ approval and posting

    public record ApproveCommand(boolean acknowledgeVariance, String note) {}

    /**
     * Approves and posts a validated batch in one transaction. A different officer with fund authority must do this; the
     * batch is validated again against the current data first, and a balance variance needs a written acknowledgement.
     * Approving a batch that is already posted returns it unchanged.
     */
    @Transactional
    public BatchView approve(UUID cooperativeId, UUID importId, ApproveCommand command) {
        authorizationService.requireMembership(cooperativeId);
        UserPrincipal actor = authorizationService.currentPrincipal();
        CooperativeOfficerRoles.requireFundAuthorize(actor);
        fundLockService.lockSchemeFund(cooperativeId);
        HistoricalLedgerImport imp = importRepository
                .findByIdForUpdate(importId, cooperativeId)
                .orElseThrow(() -> new ResourceNotFoundException("Historical import", importId));
        if (imp.getStatus() == HistoricalBatchStatus.POSTED) {
            return view(imp, false);
        }
        if (imp.getStatus() == HistoricalBatchStatus.CANCELLED) {
            throw new ConflictException("HISTORICAL_IMPORT_CANCELLED", "This import was cancelled");
        }
        if (actor.getId().equals(imp.getUploadedBy())) {
            throw new ForbiddenException("The officer who uploaded an import cannot approve it");
        }
        migrationGuard.requireStrategy(cooperativeId, MigrationStrategy.DETAILED_TRANSACTION_HISTORY, null, actor.getId());
        if (imp.getErrorCount() > 0) {
            throw new BusinessException("HISTORICAL_IMPORT_HAS_ERRORS", "This import has validation errors and cannot be approved");
        }
        boolean balanced = imp.getBalanceVariance() != null && imp.getBalanceVariance().signum() == 0;
        String note = command == null ? null : command.note();
        if (!balanced) {
            if (command == null || !command.acknowledgeVariance() || !StringUtils.hasText(note) || note.trim().length() < 10) {
                throw new BusinessException(
                        "HISTORICAL_VARIANCE_UNACKNOWLEDGED",
                        "The history does not reconcile with the declared closing balance. Explain the variance (at least 10 characters) and acknowledge it to approve.");
            }
        }

        List<HistoricalLedgerRow> stored = rowRepository.findByCooperativeIdAndImportIdOrderBySheetAscRowNumberAsc(cooperativeId, importId);
        Cooperative cooperative = cooperativeRepository
                .findByIdAndDeletedFalse(cooperativeId)
                .orElseThrow(() -> new ResourceNotFoundException("Cooperative", cooperativeId));
        List<RawRow> memberRaw = new ArrayList<>();
        List<RawRow> txRaw = new ArrayList<>();
        Map<Integer, HistoricalLedgerRow> txRowByNumber = new HashMap<>();
        for (HistoricalLedgerRow r : stored) {
            if ("MEMBERS".equals(r.getSheet())) {
                memberRaw.add(raw(r));
            } else {
                txRaw.add(raw(r));
                txRowByNumber.put(r.getRowNumber(), r);
            }
        }
        Result again = HistoricalTxValidator.validate(memberRaw, txRaw, context(cooperative));
        if (again.errorCount() > 0) {
            throw new BusinessException(
                    "HISTORICAL_IMPORT_STALE",
                    "The scheme changed since this file was validated (for example a duplicate now exists). Upload the corrected file again.");
        }

        List<Checked> ordered = new ArrayList<>(again.transactions());
        ordered.sort(Comparator.comparing((Checked c) -> c.date).thenComparingInt(c -> c.raw.rowNumber()));
        for (Checked c : ordered) {
            HistoricalLedgerRow row = txRowByNumber.get(c.raw.rowNumber());
            post(imp, row, c, cooperative, actor);
        }
        try {
            rowRepository.flush();
        } catch (DataIntegrityViolationException e) {
            throw new ConflictException("HISTORICAL_IMPORT_DUPLICATE_ROWS", "Some of these events were imported by another batch in the meantime");
        }
        HistoricalBatchStatus previous = imp.getStatus();
        imp.setStatus(HistoricalBatchStatus.POSTED);
        imp.setApprovedBy(actor.getId());
        imp.setApprovedAt(Instant.now(clock));
        imp.setVarianceAcknowledged(!balanced);
        imp.setVarianceNote(balanced ? null : trim(note, 1000));
        HistoricalLedgerImport saved = importRepository.saveAndFlush(imp);
        event(saved, actor, previous, HistoricalBatchStatus.POSTED,
                balanced ? "Approved and posted" : "Approved and posted with an acknowledged variance");
        audit(actor, saved, AuditableAction.HISTORICAL_IMPORT_POST,
                "{\"rows\":" + ordered.size() + ",\"variance\":\"" + (saved.getBalanceVariance() == null ? "" : saved.getBalanceVariance().toPlainString()) + "\"}");
        return view(saved, false);
    }

    /**
     * Posts one row: the authoritative operational record where the event has one that can be built without inventing
     * anything, and the ledger entry. Contributions become historical contribution obligations; a fine payment becomes a
     * fully paid manual fine with its approved payment; other income and expense become approved income/expense
     * transactions. Loan events and member payouts are refused (see the class documentation).
     */
    private void post(HistoricalLedgerImport imp, HistoricalLedgerRow row, Checked c, Cooperative cooperative, UserPrincipal approver) {
        HistoricalTxType type = c.txType;
        BigDecimal amount = MoneyUtils.scaleForStorage(c.amount);
        String reference = StringUtils.hasText(c.reference) ? c.reference : null;
        Instant now = Instant.now(clock);
        String sourceType = LEDGER_SOURCE;
        UUID sourceId = row.getId();
        String operationalType = null;
        UUID operationalId = null;
        switch (type) {
            case CONTRIBUTION -> {
                Contribution contribution = contributionRepository.saveAndFlush(Contribution.builder()
                        .cooperativeId(cooperative.getId())
                        .memberUserId(c.memberUserId)
                        .year(c.year)
                        .month(c.month)
                        .expectedAmount(amount)
                        .paidAmount(amount)
                        .outstandingAmount(MoneyUtils.scaleForStorage(BigDecimal.ZERO))
                        .status(ContributionStatus.PAID)
                        .paymentDate(c.date)
                        .paymentReference(reference)
                        .notes("Historical import " + imp.getId())
                        .recordedBy(imp.getUploadedBy())
                        .ledgerRevision(1)
                        // Only what the workbook states, and only when it states it: no due date, no split, no rotation
                        // period and no recipient are invented, so the amount can never become payout-eligible.
                        .obligationSnapshotAt(StringUtils.hasText(c.model) ? now : null)
                        .operatingModelSnapshot(StringUtils.hasText(c.model) ? c.model : null)
                        .build());
                sourceType = LedgerService.SOURCE_CONTRIBUTION;
                sourceId = contribution.getId();
                operationalType = "CONTRIBUTION";
                operationalId = contribution.getId();
                row.setContributionId(contribution.getId());
            }
            case FINE_PAYMENT -> {
                // The fine assessed amount was not recorded in the original books; the paid amount is all that is known,
                // so the fine is recorded as exactly that amount, fully paid, and says so.
                Fine fine = fineRepository.saveAndFlush(Fine.builder()
                        .cooperativeId(cooperative.getId())
                        .memberUserId(c.memberUserId)
                        .fineType(FineType.MANUAL)
                        .calculationMode(FineCalculationMode.FIXED)
                        .baseAmount(amount)
                        .totalAmount(amount)
                        .paidAmount(amount)
                        .outstandingAmount(MoneyUtils.scaleForStorage(BigDecimal.ZERO))
                        .reason("Historical fine payment (import)")
                        .notes("Historical import " + imp.getId() + ": the assessed amount was not recorded; only the payment is known.")
                        .issuedDate(c.date)
                        .dueDate(c.date)
                        .status(FineStatus.PAID)
                        .issuedBy(imp.getUploadedBy())
                        .build());
                FinePayment payment = finePaymentRepository.saveAndFlush(FinePayment.builder()
                        .fineId(fine.getId())
                        .cooperativeId(cooperative.getId())
                        .memberUserId(c.memberUserId)
                        .amount(amount)
                        .paymentDate(c.date)
                        .paymentReference(reference)
                        .paymentMethod(FinePaymentMethod.OTHER)
                        .paymentMethodDetail("Historical import (method not recorded)")
                        .notes("Historical import " + imp.getId())
                        .status(FinePaymentStatus.APPROVED)
                        .submittedBy(imp.getUploadedBy())
                        .reviewedBy(approver.getId())
                        .reviewedAt(now)
                        .reviewNotes("Approved with the historical import")
                        .build());
                sourceType = LedgerService.SOURCE_FINE_PAYMENT;
                sourceId = payment.getId();
                operationalType = "FINE_PAYMENT";
                operationalId = payment.getId();
            }
            case OTHER_INCOME, GENERAL_EXPENSE -> {
                IncomeExpenseTransaction tx = incomeExpenseRepository.saveAndFlush(IncomeExpenseTransaction.builder()
                        .cooperativeId(cooperative.getId())
                        .category(type == HistoricalTxType.OTHER_INCOME ? IncomeExpenseCategory.OTHER_INCOME : IncomeExpenseCategory.GENERAL_EXPENSE)
                        .amount(amount)
                        .transactionDate(c.date)
                        .reference(reference)
                        .description(trim(c.description, 2000))
                        .notes("Historical import " + imp.getId())
                        .approvalStatus(IncomeExpenseApprovalStatus.APPROVED)
                        .recordedBy(imp.getUploadedBy())
                        .approvedBy(approver.getId())
                        .approvedAt(now)
                        .build());
                sourceType = LedgerService.SOURCE_INCOME_EXPENSE;
                sourceId = tx.getId();
                operationalType = "INCOME_EXPENSE";
                operationalId = tx.getId();
            }
            default -> throw new BusinessException("HISTORICAL_ROW_TYPE_UNSUPPORTED",
                    type.name() + " rows cannot be posted: they have no authoritative record to update");
        }
        boolean credit = type.credit();
        LedgerEntry entry = ledgerService.appendApproved(LedgerService.AppendRequest.builder()
                .cooperativeId(cooperative.getId())
                .memberUserId(c.memberUserId)
                .transactionType(type.ledgerType())
                .debitAmount(credit ? BigDecimal.ZERO : amount)
                .creditAmount(credit ? amount : BigDecimal.ZERO)
                .currency(cooperative.getCurrency())
                .transactionDate(c.date)
                .reference(reference)
                .sourceEntityType(sourceType)
                .sourceEntityId(sourceId)
                .description(description(type, c))
                .recordedBy(imp.getUploadedBy())
                .approvedBy(approver.getId())
                .idempotencyKey(KEY_PREFIX + imp.getId() + ":" + row.getId())
                .build());
        row.setPostedKey(c.fingerprint);
        row.setFingerprint(c.fingerprint);
        row.setLedgerEntryId(entry.getId());
        row.setOperationalType(operationalType);
        row.setOperationalId(operationalId);
        rowRepository.save(row);
    }

    // ------------------------------------------------------------------ cancel

    @Transactional
    public BatchView cancel(UUID cooperativeId, UUID importId, String note) {
        UserPrincipal actor = requireUploader(cooperativeId);
        HistoricalLedgerImport imp = importRepository
                .findByIdForUpdate(importId, cooperativeId)
                .orElseThrow(() -> new ResourceNotFoundException("Historical import", importId));
        if (imp.getStatus() == HistoricalBatchStatus.CANCELLED) {
            return view(imp, false);
        }
        if (imp.getStatus() == HistoricalBatchStatus.POSTED) {
            throw new ConflictException("HISTORICAL_IMPORT_POSTED", "A posted import is final and cannot be cancelled");
        }
        HistoricalBatchStatus previous = imp.getStatus();
        imp.setStatus(HistoricalBatchStatus.CANCELLED);
        imp.setCancelledBy(actor.getId());
        imp.setCancelledAt(Instant.now(clock));
        imp.setActiveKey(null);
        HistoricalLedgerImport saved = importRepository.saveAndFlush(imp);
        event(saved, actor, previous, HistoricalBatchStatus.CANCELLED, trim(note, 1000));
        audit(actor, saved, AuditableAction.HISTORICAL_IMPORT_CANCEL, null);
        return view(saved, false);
    }

    // ------------------------------------------------------------------ reads

    @Transactional(readOnly = true)
    public List<BatchView> list(UUID cooperativeId) {
        requireViewer(cooperativeId);
        return importRepository.findByCooperativeIdOrderByUploadedAtDesc(cooperativeId).stream().map(i -> view(i, false)).toList();
    }

    @Transactional(readOnly = true)
    public BatchView get(UUID cooperativeId, UUID importId) {
        requireViewer(cooperativeId);
        return view(find(cooperativeId, importId), false);
    }

    @Transactional(readOnly = true)
    public List<RowView> rows(UUID cooperativeId, UUID importId, boolean onlyErrors) {
        requireViewer(cooperativeId);
        find(cooperativeId, importId);
        List<RowView> views = new ArrayList<>();
        for (HistoricalLedgerRow r : rowRepository.findByCooperativeIdAndImportIdOrderBySheetAscRowNumberAsc(cooperativeId, importId)) {
            if (onlyErrors && r.isValid()) {
                continue;
            }
            views.add(new RowView(
                    r.getSheet(), r.getRowNumber(), r.getRowType(), r.getUsername(), r.getEventDate(), r.getAmount(),
                    r.getOriginalReference(), r.getPeriodYear(), r.getPeriodMonth(), r.getLoanReference(), r.getOperatingModel(),
                    r.getSettingKey(), r.getSettingValue(), r.isValid(), issues(r.getErrors()), r.getPostedKey() != null));
        }
        return views;
    }

    @Transactional(readOnly = true)
    public List<EventView> events(UUID cooperativeId, UUID importId) {
        requireViewer(cooperativeId);
        find(cooperativeId, importId);
        return eventRepository.findByCooperativeIdAndImportIdOrderByCreatedAtAsc(cooperativeId, importId).stream()
                .map(e -> new EventView(e.getActorUserId(), e.getActorRole(), e.getFromStatus(), e.getToStatus(), e.getComment(), e.getCreatedAt()))
                .toList();
    }

    // ------------------------------------------------------------------ internals

    private Context context(Cooperative cooperative) {
        UUID id = cooperative.getId();
        List<CooperativeMembership> memberships = membershipRepository.findByCooperativeId(id);
        Map<UUID, Integer> shares = new HashMap<>();
        for (CooperativeMembership m : memberships) {
            shares.put(m.getUserId(), m.getShareCount());
        }
        Map<String, UUID> byUsername = new HashMap<>();
        for (User u : userRepository.findAllById(shares.keySet())) {
            byUsername.put(u.getUsername().toLowerCase(Locale.ROOT), u.getId());
        }
        Set<String> contributionKeys = new HashSet<>();
        for (Object[] key : contributionRepository.findPeriodKeys(id)) {
            contributionKeys.add(key[0] + ":" + key[1] + ":" + key[2]);
        }
        return new Context(
                id,
                cooperative.getCurrency() == null ? "RWF" : cooperative.getCurrency(),
                cooperative.getOperatingModel(),
                byUsername,
                shares,
                contributionKeys,
                ledgerEntryRepository.earliestLiveTransactionDate(id),
                ContributionDueDates.today(clock),
                keys -> new HashSet<>(rowRepository.findPostedKeys(id, keys)));
    }

    private HistoricalLedgerRow row(HistoricalLedgerImport imp, String sheet, Checked c) {
        return HistoricalLedgerRow.builder()
                .importId(imp.getId())
                .cooperativeId(imp.getCooperativeId())
                .sheet(sheet)
                .rowNumber(c.raw.rowNumber())
                .rowType(c.rowType == null || c.rowType.isEmpty() ? "UNKNOWN" : trim(c.rowType, 40))
                .username(trim(c.username, 100))
                .memberUserId(c.memberUserId)
                .eventDate(c.date)
                .amount(c.amount)
                .originalReference(trim(c.reference, 128))
                .periodYear(c.year)
                .periodMonth(c.month)
                .loanReference(trim(c.loanReference, 64))
                .operatingModel(StringUtils.hasText(c.model) ? trim(c.model, 16) : null)
                .description(trim(c.description, 500))
                .settingKey(trim(c.settingKey, 40))
                .settingValue(trim(c.settingValue, 100))
                .fingerprint(c.fingerprint)
                .valid(c.valid())
                .errors(c.issues.isEmpty() ? null : json(c.issues))
                .build();
    }

    private RawRow raw(HistoricalLedgerRow r) {
        Map<String, String> cells = new LinkedHashMap<>();
        cells.put("Row Type", nz(r.getRowType()));
        if ("MEMBERS".equals(r.getSheet())) {
            cells.put("Username", nz(r.getUsername()));
            cells.put("Setting", nz(r.getSettingKey()));
            cells.put("Value", nz(r.getSettingValue()));
        } else {
            cells.put("Date", r.getEventDate() == null ? "" : r.getEventDate().toString());
            cells.put("Member Username", nz(r.getUsername()));
            cells.put("Amount", r.getAmount() == null ? "" : r.getAmount().toPlainString());
            cells.put("Original Reference", nz(r.getOriginalReference()));
            cells.put("Period Year", r.getPeriodYear() == null ? "" : String.valueOf(r.getPeriodYear()));
            cells.put("Period Month", r.getPeriodMonth() == null ? "" : String.valueOf(r.getPeriodMonth()));
            cells.put("Loan Reference", nz(r.getLoanReference()));
            cells.put("Operating Model At The Time", nz(r.getOperatingModel()));
            cells.put("Description", nz(r.getDescription()));
        }
        return new RawRow(r.getSheet(), r.getRowNumber(), cells);
    }

    private String summaryJson(Result result) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("reconciliation", result.reconciliation());
        summary.put("warnings", result.warnings());
        summary.put("sheetIssues", result.sheetIssues());
        return json(summary);
    }

    private BatchView view(HistoricalLedgerImport i, boolean reused) {
        JsonNode summary = null;
        if (StringUtils.hasText(i.getSummary())) {
            try {
                summary = objectMapper.readTree(i.getSummary());
            } catch (JsonProcessingException ignored) {
                summary = null;
            }
        }
        return new BatchView(
                i.getId(), i.getStatus(), i.getFileName(), reused, i.getTotalRows(), i.getValidRows(), i.getErrorRows(),
                i.getErrorCount(), i.getStatus() == HistoricalBatchStatus.PREVIEWED && i.getErrorCount() == 0,
                i.getCurrency(), i.getHistoryEndDate(), i.getOpeningBalance(), i.getDeclaredClosingBalance(),
                i.getComputedClosingBalance(), i.getBalanceVariance(), i.isVarianceAcknowledged(), i.getVarianceNote(),
                i.getUploadedBy(), i.getUploadedAt(), i.getApprovedBy(), i.getApprovedAt(), i.getCancelledAt(), summary);
    }

    private HistoricalLedgerImport find(UUID cooperativeId, UUID importId) {
        return importRepository.findByIdAndCooperativeId(importId, cooperativeId)
                .orElseThrow(() -> new ResourceNotFoundException("Historical import", importId));
    }

    private byte[] readFile(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw ValidationException.coded("FILE_REQUIRED", "An Excel file is required");
        }
        if (file.getSize() > MAX_BYTES) {
            throw ValidationException.coded("FILE_TOO_LARGE", "The file is larger than " + (MAX_BYTES / 1024 / 1024) + " MB");
        }
        String name = fileName(file).toLowerCase(Locale.ROOT);
        if (!name.endsWith(".xlsx")) {
            throw ValidationException.coded("FILE_NOT_XLSX", "Only .xlsx workbooks are accepted");
        }
        try {
            byte[] bytes = file.getBytes();
            if (bytes.length < 4 || bytes[0] != 'P' || bytes[1] != 'K') {
                throw ValidationException.coded("FILE_NOT_XLSX", "The file is not an .xlsx workbook");
            }
            return bytes;
        } catch (IOException e) {
            throw ValidationException.coded("FILE_UNREADABLE", "The file could not be read");
        }
    }

    private static String fileName(MultipartFile file) {
        String name = file.getOriginalFilename();
        if (!StringUtils.hasText(name)) {
            return "historical-import.xlsx";
        }
        String clean = name.replace('\\', '/');
        clean = clean.substring(clean.lastIndexOf('/') + 1);
        return clean.length() > 255 ? clean.substring(clean.length() - 255) : clean;
    }

    private static String description(HistoricalTxType type, Checked c) {
        StringBuilder text = new StringBuilder("Historical ").append(type.name());
        if (StringUtils.hasText(c.loanReference)) {
            text.append(" loan ").append(c.loanReference);
        }
        if (c.year != null) {
            text.append(' ').append(c.year).append('-').append(String.format("%02d", c.month));
        }
        if (StringUtils.hasText(c.description)) {
            text.append(": ").append(c.description);
        }
        return trim(text.toString(), 1000);
    }

    private void event(HistoricalLedgerImport imp, UserPrincipal actor, HistoricalBatchStatus from, HistoricalBatchStatus to, String comment) {
        eventRepository.save(HistoricalLedgerImportEvent.builder()
                .importId(imp.getId())
                .cooperativeId(imp.getCooperativeId())
                .actorUserId(actor.getId())
                .actorRole(CooperativeOfficerRoles.displayRole(actor))
                .fromStatus(from)
                .toStatus(to)
                .comment(comment)
                .createdAt(Instant.now(clock))
                .build());
    }

    private void audit(UserPrincipal actor, HistoricalLedgerImport imp, AuditableAction action, String json) {
        auditService.record(actor.getId(), imp.getCooperativeId(), action, "HistoricalLedgerImport", imp.getId(), null, json, null, null);
    }

    private UserPrincipal requireUploader(UUID cooperativeId) {
        authorizationService.requireMembership(cooperativeId);
        UserPrincipal actor = authorizationService.currentPrincipal();
        if (!actor.hasRole(CooperativeAuthorizationService.SUPER_ADMIN)
                && !actor.hasAuthority(CONTRIBUTION_WRITE)
                && !actor.hasAuthority(FUND_AUTHORIZE)) {
            throw new ForbiddenException("You do not have permission to import historical data");
        }
        return actor;
    }

    private UserPrincipal requireViewer(UUID cooperativeId) {
        return requireUploader(cooperativeId);
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private List<Issue> issues(String json) {
        if (!StringUtils.hasText(json)) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<Issue>>() {});
        } catch (JsonProcessingException e) {
            return List.of();
        }
    }

    private static String sha256(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String nz(String value) {
        return value == null ? "" : value;
    }

    private static String trim(String value, int max) {
        if (value == null) {
            return null;
        }
        String t = value.trim();
        if (t.isEmpty()) {
            return null;
        }
        return t.length() > max ? t.substring(0, max) : t;
    }

    // ------------------------------------------------------------------ DTOs

    /** @param summary the stored report: reconciliation (per year, per type, loans, unresolved attribution), warnings, sheet issues */
    public record BatchView(
            UUID id,
            HistoricalBatchStatus status,
            String fileName,
            boolean reused,
            int totalRows,
            int validRows,
            int errorRows,
            int errorCount,
            boolean canApprove,
            String currency,
            LocalDate historyEnd,
            BigDecimal openingBalance,
            BigDecimal declaredClosingBalance,
            BigDecimal computedClosingBalance,
            BigDecimal balanceVariance,
            boolean varianceAcknowledged,
            String varianceNote,
            UUID uploadedBy,
            Instant uploadedAt,
            UUID approvedBy,
            Instant approvedAt,
            Instant cancelledAt,
            JsonNode summary) {}

    public record RowView(
            String sheet,
            int rowNumber,
            String rowType,
            String username,
            LocalDate date,
            BigDecimal amount,
            String originalReference,
            Integer periodYear,
            Integer periodMonth,
            String loanReference,
            String operatingModel,
            String settingKey,
            String settingValue,
            boolean valid,
            List<Issue> errors,
            boolean posted) {}

    public record EventView(
            UUID actorUserId, String actorRole, HistoricalBatchStatus fromStatus, HistoricalBatchStatus toStatus, String comment, Instant createdAt) {}
}
