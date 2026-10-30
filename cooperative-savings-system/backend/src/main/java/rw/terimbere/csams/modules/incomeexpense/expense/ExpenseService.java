package rw.terimbere.csams.modules.incomeexpense.expense;

import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.math.BigDecimal;
import java.sql.Date;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import rw.terimbere.csams.modules.audit.service.AuditService;
import rw.terimbere.csams.modules.filemanagement.dto.StoredFileResponse;
import rw.terimbere.csams.modules.filemanagement.service.FileManagementService;
import rw.terimbere.csams.modules.incomeexpense.entity.IncomeExpenseApprovalStatus;
import rw.terimbere.csams.modules.incomeexpense.entity.IncomeExpenseCategory;
import rw.terimbere.csams.modules.incomeexpense.entity.IncomeExpenseTransaction;
import rw.terimbere.csams.modules.incomeexpense.repository.IncomeExpenseTransactionRepository;
import rw.terimbere.csams.modules.memberpayment.receipt.ReceiptReader;
import rw.terimbere.csams.modules.reconciliation.StatementMatcher;
import rw.terimbere.csams.security.CooperativeAuthorizationService;
import rw.terimbere.csams.security.UserPrincipal;
import rw.terimbere.csams.shared.auditing.AuditableAction;
import rw.terimbere.csams.shared.exceptions.BusinessException;
import rw.terimbere.csams.shared.exceptions.ForbiddenException;
import rw.terimbere.csams.shared.exceptions.ValidationException;
import rw.terimbere.csams.shared.utilities.MoneyUtils;

/**
 * Scheme expenses: what the group spends (meetings, meals, transport, stationery...) and what the bank takes (charges).
 * Every expense carries its supporting document, read on upload so the officer approving it sees whether the amount
 * recorded is the amount the document shows. Bank charges are supported by the bank statement line instead, and once
 * approved they reconcile that line. Approval and ledger posting are the existing income/expense workflow.
 */
@Service
@RequiredArgsConstructor
public class ExpenseService {

    public static final Set<String> TYPES = Set.of("BANK_CHARGE", "MEETING", "MEALS_AND_REFRESHMENTS", "TRANSPORT",
            "STATIONERY_AND_PRINTING", "COMMUNICATION", "VENUE", "SUPPORT_AND_GIFTS", "OTHER");
    private static final long MAX_FILE_BYTES = 10L * 1024 * 1024;

    private final JdbcTemplate jdbc;
    private final IncomeExpenseTransactionRepository transactions;
    private final FileManagementService files;
    private final ReceiptReader receiptReader;
    private final CooperativeAuthorizationService authorization;
    private final AuditService auditService;

    public record ExpenseView(UUID id, String expenseType, BigDecimal amount, LocalDate date, String description, String reference,
            String status, String recordedBy, String approvedBy, String rejectionReason, String evidenceKey, String evidenceFileName,
            String evidenceCheck, BigDecimal evidenceAmountRead, String evidenceNote, UUID statementLineId, boolean mine) {}

    public record Overview(boolean canRecord, boolean canApprove, List<ExpenseView> expenses, int pendingCount, BigDecimal pendingAmount,
            BigDecimal approvedThisMonth, Map<String, BigDecimal> byTypeThisYear, int bankChargeLinesToRecord) {}

    public record BankChargeLine(UUID lineId, UUID statementId, String statementFile, LocalDate date, String narration, BigDecimal amount,
            String lineStatus, boolean looksLikeCharge) {}

    public record RecordRequest(String expenseType, BigDecimal amount, LocalDate date, String description, String reference,
            String explanation) {}

    // ------------------------------------------------------------------ reading

    @Transactional(readOnly = true)
    public Overview overview(UUID cooperativeId) {
        UserPrincipal me = requireRead(cooperativeId);
        List<ExpenseView> list = jdbc.query("select t.id, t.expense_type, t.amount, t.transaction_date, t.description, t.reference, t.approval_status,"
                        + " trim(coalesce(r.first_name,'') || ' ' || coalesce(r.last_name,'')), trim(coalesce(a.first_name,'') || ' ' || coalesce(a.last_name,'')),"
                        + " t.rejection_reason, t.supporting_file_key, t.evidence_file_name, t.evidence_check, t.evidence_amount_read, t.evidence_note,"
                        + " t.statement_line_id, t.recorded_by"
                        + " from income_expense_transactions t left join users r on r.id = t.recorded_by left join users a on a.id = t.approved_by"
                        + " where t.cooperative_id = ? and t.category = 'GENERAL_EXPENSE' order by t.transaction_date desc, t.created_at desc",
                (rs, i) -> new ExpenseView(rs.getObject(1, UUID.class), rs.getString(2) == null ? "OTHER" : rs.getString(2), rs.getBigDecimal(3),
                        rs.getDate(4).toLocalDate(), rs.getString(5), rs.getString(6), rs.getString(7), blank(rs.getString(8)),
                        blank(rs.getString(9)), rs.getString(10), rs.getString(11), rs.getString(12), rs.getString(13), rs.getBigDecimal(14),
                        rs.getString(15), rs.getObject(16, UUID.class), me.getId().equals(rs.getObject(17, UUID.class))),
                cooperativeId);
        BigDecimal pending = BigDecimal.ZERO;
        int pendingCount = 0;
        BigDecimal month = BigDecimal.ZERO;
        Map<String, BigDecimal> byType = new LinkedHashMap<>();
        LocalDate today = LocalDate.now(java.time.ZoneId.of("Africa/Kigali"));
        for (ExpenseView e : list) {
            if ("PENDING".equals(e.status())) {
                pending = pending.add(e.amount());
                pendingCount++;
            }
            if ("APPROVED".equals(e.status())) {
                if (e.date().getYear() == today.getYear() && e.date().getMonth() == today.getMonth()) {
                    month = month.add(e.amount());
                }
                if (e.date().getYear() == today.getYear()) {
                    byType.merge(e.expenseType(), e.amount(), BigDecimal::add);
                }
            }
        }
        int toRecord = bankChargeLines(cooperativeId, me).size();
        return new Overview(canRecord(me), canApprove(me), list, pendingCount, MoneyUtils.scale(pending), MoneyUtils.scale(month), byType, toRecord);
    }

    /** Money-out lines of uploaded statements not yet accounted for by any record: the bank charges to record. */
    @Transactional(readOnly = true)
    public List<BankChargeLine> bankChargeLines(UUID cooperativeId) {
        return bankChargeLines(cooperativeId, requireRead(cooperativeId));
    }

    private List<BankChargeLine> bankChargeLines(UUID cooperativeId, UserPrincipal me) {
        return jdbc.query("select l.id, l.statement_id, s.original_filename, l.transaction_date, l.narration, l.amount, l.status, l.category"
                        + " from bank_statement_lines l join bank_statements s on s.id = l.statement_id"
                        + " where l.cooperative_id = ? and l.direction = 'OUT'"
                        + " and (l.status in ('UNMATCHED','SUGGESTED') or (l.status = 'EXPLAINED' and l.category = 'BANK_CHARGE'))"
                        + " and not exists (select 1 from income_expense_transactions t where t.statement_line_id = l.id and t.approval_status <> 'REJECTED')"
                        + " order by l.transaction_date, l.line_number",
                (rs, i) -> new BankChargeLine(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getString(3),
                        rs.getDate(4).toLocalDate(), rs.getString(5), rs.getBigDecimal(6), rs.getString(7),
                        "BANK_CHARGE".equals(rs.getString(8)) || StatementMatcher.looksLikeBankCharge(rs.getString(5))),
                cooperativeId)
                .stream()
                .filter(BankChargeLine::looksLikeCharge)
                .toList();
    }

    // ------------------------------------------------------------------ recording

    /**
     * Records an expense with its supporting document. The document is read first: when it clearly shows another amount
     * the expense is refused unless the recorder explains the difference; a document that cannot be read is accepted
     * and flagged for the approver to check by eye.
     */
    @Transactional
    public ExpenseView record(UUID cooperativeId, MultipartFile file, RecordRequest request, HttpServletRequest http) {
        UserPrincipal me = requireRecord(cooperativeId);
        String type = type(request.expenseType());
        if ("BANK_CHARGE".equals(type)) {
            throw new BusinessException("BANK_CHARGE_FROM_STATEMENT",
                    "Record bank charges from the bank statement lines (upload the statement in Reconciliation)");
        }
        BigDecimal amount = positive(request.amount());
        LocalDate date = date(request.date());
        String description = trim(request.description(), 2000);
        if (description == null) {
            throw ValidationException.coded("DESCRIPTION_REQUIRED", "Say what the expense was for");
        }
        if (file == null || file.isEmpty()) {
            throw ValidationException.coded("EVIDENCE_REQUIRED", "Attach the receipt, invoice or bill for this expense");
        }
        if (file.getSize() > MAX_FILE_BYTES) {
            throw ValidationException.coded("FILE_TOO_LARGE", "The document must be 10 MB or smaller");
        }
        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException e) {
            throw ValidationException.coded("EVIDENCE_UNREADABLE", "The document could not be read");
        }
        ExpenseEvidenceCheck.Result check = ExpenseEvidenceCheck.check(amount, safeRead(bytes, file.getContentType()));
        String explanation = trim(request.explanation(), 500);
        if (ExpenseEvidenceCheck.MISMATCH.equals(check.check()) && (explanation == null || explanation.length() < 5)) {
            throw new BusinessException("EVIDENCE_AMOUNT_MISMATCH", check.amountRead() == null
                    ? "The amount " + plain(amount) + " was not found on the document. Correct the amount, or explain why it differs."
                    : "The document shows " + plain(check.amountRead()) + ", not " + plain(amount)
                            + ". Correct the amount, or explain why it differs.");
        }
        StoredFileResponse stored = files.storeDocument(cooperativeId, file, "INCOME_EXPENSE_DOCUMENT", me, ip(http), agent(http));
        String note = ExpenseEvidenceCheck.MISMATCH.equals(check.check()) ? explanation : check.note();
        IncomeExpenseTransaction tx = transactions.save(IncomeExpenseTransaction.builder()
                .cooperativeId(cooperativeId)
                .category(IncomeExpenseCategory.GENERAL_EXPENSE)
                .amount(MoneyUtils.scaleForStorage(amount))
                .transactionDate(date)
                .reference(trim(request.reference(), 128))
                .description(description)
                .supportingFileKey(stored.getStorageKey())
                .approvalStatus(IncomeExpenseApprovalStatus.PENDING)
                .recordedBy(me.getId())
                .expenseType(type)
                .evidenceCheck(check.check())
                .evidenceAmountRead(check.amountRead() == null ? null : MoneyUtils.scaleForStorage(check.amountRead()))
                .evidenceNote(note == null ? null : note.length() > 500 ? note.substring(0, 500) : note)
                .evidenceFileName(trim(file.getOriginalFilename(), 255))
                .build());
        audit(me, cooperativeId, tx, http);
        return view(cooperativeId, tx.getId(), me);
    }

    /** Records bank charges from statement lines: one expense per line, the statement as its evidence. */
    @Transactional
    public List<ExpenseView> recordBankCharges(UUID cooperativeId, List<UUID> lineIds, HttpServletRequest http) {
        UserPrincipal me = requireRecord(cooperativeId);
        if (lineIds == null || lineIds.isEmpty()) {
            throw new BusinessException("NOTHING_TO_RECORD", "Choose the bank charges to record");
        }
        Set<UUID> open = new java.util.HashSet<>(bankChargeLines(cooperativeId, me).stream().map(BankChargeLine::lineId).toList());
        List<ExpenseView> out = new ArrayList<>();
        for (UUID lineId : lineIds) {
            if (!open.contains(lineId)) {
                throw new BusinessException("LINE_NOT_A_CHARGE_TO_RECORD", "One of the lines is already accounted for or is not a bank charge");
            }
            Map<String, Object> l = jdbc.queryForMap("select l.transaction_date, l.narration, l.amount, l.reference, s.storage_key, s.original_filename"
                    + " from bank_statement_lines l join bank_statements s on s.id = l.statement_id where l.id = ?", lineId);
            IncomeExpenseTransaction tx = transactions.save(IncomeExpenseTransaction.builder()
                    .cooperativeId(cooperativeId)
                    .category(IncomeExpenseCategory.GENERAL_EXPENSE)
                    .amount(MoneyUtils.scaleForStorage(new BigDecimal(l.get("amount").toString())))
                    .transactionDate(((Date) l.get("transaction_date")).toLocalDate())
                    .reference(trim((String) l.get("reference"), 128))
                    .description(trim("Bank charge: " + l.get("narration"), 2000))
                    .supportingFileKey((String) l.get("storage_key"))
                    .approvalStatus(IncomeExpenseApprovalStatus.PENDING)
                    .recordedBy(me.getId())
                    .expenseType("BANK_CHARGE")
                    .evidenceCheck(ExpenseEvidenceCheck.STATEMENT_LINE)
                    .evidenceAmountRead(MoneyUtils.scaleForStorage(new BigDecimal(l.get("amount").toString())))
                    .evidenceFileName(trim((String) l.get("original_filename"), 255))
                    .statementLineId(lineId)
                    .build());
            audit(me, cooperativeId, tx, http);
            out.add(view(cooperativeId, tx.getId(), me));
        }
        return out;
    }

    // ------------------------------------------------------------------ helpers

    private ReceiptReader.Outcome safeRead(byte[] bytes, String contentType) {
        try {
            return receiptReader.read(bytes, contentType);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private ExpenseView view(UUID cooperativeId, UUID id, UserPrincipal me) {
        return overviewRows(cooperativeId, me).stream().filter(v -> v.id().equals(id)).findFirst().orElseThrow();
    }

    private List<ExpenseView> overviewRows(UUID cooperativeId, UserPrincipal me) {
        return jdbc.query("select t.id, t.expense_type, t.amount, t.transaction_date, t.description, t.reference, t.approval_status,"
                        + " t.rejection_reason, t.supporting_file_key, t.evidence_file_name, t.evidence_check, t.evidence_amount_read,"
                        + " t.evidence_note, t.statement_line_id, t.recorded_by"
                        + " from income_expense_transactions t where t.cooperative_id = ? and t.category = 'GENERAL_EXPENSE'",
                (rs, i) -> new ExpenseView(rs.getObject(1, UUID.class), rs.getString(2), rs.getBigDecimal(3), rs.getDate(4).toLocalDate(),
                        rs.getString(5), rs.getString(6), rs.getString(7), "", "", rs.getString(8), rs.getString(9), rs.getString(10),
                        rs.getString(11), rs.getBigDecimal(12), rs.getString(13), rs.getObject(14, UUID.class),
                        me.getId().equals(rs.getObject(15, UUID.class))),
                cooperativeId);
    }

    private void audit(UserPrincipal me, UUID cooperativeId, IncomeExpenseTransaction tx, HttpServletRequest http) {
        auditService.record(me.getId(), cooperativeId, AuditableAction.INCOME_EXPENSE_CREATE, "IncomeExpenseTransaction", tx.getId(), null,
                "{\"category\":\"GENERAL_EXPENSE\",\"expenseType\":\"" + tx.getExpenseType() + "\",\"amount\":\"" + tx.getAmount()
                        + "\",\"evidence\":\"" + tx.getEvidenceCheck() + "\",\"status\":\"PENDING\"}",
                ip(http), agent(http));
    }

    private UserPrincipal requireRead(UUID cooperativeId) {
        authorization.requireMembership(cooperativeId);
        UserPrincipal me = authorization.currentPrincipal();
        if (!me.hasAuthority("INCOME_EXPENSE_READ") && !me.hasAuthority("INCOME_EXPENSE_WRITE") && !me.hasAuthority("FUND_AUTHORIZE")
                && !me.hasRole(CooperativeAuthorizationService.SUPER_ADMIN)) {
            throw new ForbiddenException("Viewing the scheme's expenses is for officers");
        }
        return me;
    }

    private UserPrincipal requireRecord(UUID cooperativeId) {
        UserPrincipal me = requireRead(cooperativeId);
        if (!canRecord(me)) {
            throw new ForbiddenException("Recording expenses needs the income and expense right");
        }
        return me;
    }

    private static boolean canRecord(UserPrincipal me) {
        return me.hasAuthority("INCOME_EXPENSE_WRITE") || me.hasRole(CooperativeAuthorizationService.SUPER_ADMIN);
    }

    private static boolean canApprove(UserPrincipal me) {
        return me.hasAuthority("FUND_AUTHORIZE") || me.hasRole(CooperativeAuthorizationService.SUPER_ADMIN);
    }

    private static String type(String raw) {
        String t = raw == null ? "OTHER" : raw.trim().toUpperCase(Locale.ROOT);
        if (!TYPES.contains(t)) {
            throw ValidationException.coded("INVALID_EXPENSE_TYPE", "Choose an expense type from the list");
        }
        return t;
    }

    private static BigDecimal positive(BigDecimal amount) {
        if (amount == null || amount.signum() <= 0) {
            throw ValidationException.coded("AMOUNT_REQUIRED", "Enter the amount spent");
        }
        return amount;
    }

    private static LocalDate date(LocalDate date) {
        if (date == null) {
            throw ValidationException.coded("DATE_REQUIRED", "Enter the date of the expense");
        }
        if (date.isAfter(LocalDate.now(java.time.ZoneId.of("Africa/Kigali")))) {
            throw ValidationException.coded("DATE_IN_FUTURE", "The date cannot be in the future");
        }
        return date;
    }

    private static String trim(String value, int max) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String t = value.trim();
        return t.length() > max ? t.substring(0, max) : t;
    }

    private static String plain(BigDecimal v) {
        return String.format(Locale.ROOT, "%,.0f", v);
    }

    private static String blank(String v) {
        return v == null || v.isBlank() ? null : v;
    }

    private static String ip(HttpServletRequest r) {
        if (r == null) return null;
        String f = r.getHeader("X-Forwarded-For");
        return f != null && !f.isBlank() ? f.split(",")[0].trim() : r.getRemoteAddr();
    }

    private static String agent(HttpServletRequest r) {
        return r == null ? null : r.getHeader("User-Agent");
    }
}
