package rw.terimbere.csams.modules.reconciliation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Date;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import rw.terimbere.csams.modules.audit.service.AuditService;
import rw.terimbere.csams.modules.position.MemberPositionService;
import rw.terimbere.csams.security.CooperativeAuthorizationService;
import rw.terimbere.csams.security.CooperativeOfficerRoles;
import rw.terimbere.csams.security.UserPrincipal;
import rw.terimbere.csams.shared.auditing.AuditableAction;
import rw.terimbere.csams.shared.exceptions.BusinessException;
import rw.terimbere.csams.shared.exceptions.ForbiddenException;
import rw.terimbere.csams.shared.exceptions.ResourceNotFoundException;
import rw.terimbere.csams.shared.exceptions.ValidationException;
import rw.terimbere.csams.shared.file.FileStorageService;

/**
 * Bank reconciliation: an officer uploads a bank or mobile-money statement, every line is matched with what the app
 * recorded (member payments, and ledger entries such as loans given out, payouts and expenses), and what cannot be
 * linked with confidence is left for an officer.
 *
 * <p>Officers see it; the Accountant, the President and the Vice President change it (upload, confirm, link, explain,
 * undo, verify). A statement line only ever explains records of the same scheme, each record is reconciled with one line
 * at most, and the same line in an overlapping statement is recognised and kept once.
 */
@Service
@RequiredArgsConstructor
public class ReconciliationService {

    private static final long MAX_FILE_BYTES = 10L * 1024 * 1024;
    private static final Set<String> IN_TYPES = Set.of("REGULAR_CONTRIBUTION", "SPECIAL_CONTRIBUTION", "LOAN_PRINCIPAL_REPAYMENT",
            "LOAN_INTEREST_PAYMENT", "FINE_PAYMENT", "SHARE_PURCHASE", "SOCIAL_CONTRIBUTION", "OTHER_INCOME",
            "INVESTMENT_CAPITAL_RETURN", "INVESTMENT_PROFIT", "MEMBER_CREDIT_RECEIVED");
    private static final Set<String> OUT_TYPES = Set.of("LOAN_DISBURSEMENT", "MEMBER_PAYOUT", "GENERAL_EXPENSE", "INVESTMENT_OUTFLOW",
            "SOCIAL_DISBURSEMENT", "INTEREST_EXPENSE");
    public static final Set<String> CATEGORIES = Set.of("BANK_CHARGE", "BANK_INTEREST", "TRANSFER_BETWEEN_ACCOUNTS", "NOT_SCHEME_MONEY",
            "RECORDED_ELSEWHERE", "OTHER");

    private final JdbcTemplate jdbc;
    private final CooperativeAuthorizationService authorizationService;
    private final FileStorageService fileStorageService;
    private final AuditService auditService;
    private final ObjectMapper objectMapper;
    private final MemberPositionService positionService;
    private final StatementVerification verification;

    // ------------------------------------------------------------------ DTOs

    public record StatementSummary(UUID id, String source, String accountLabel, String originalFilename, LocalDate periodFrom,
            LocalDate periodTo, BigDecimal openingBalance, BigDecimal closingBalance, BigDecimal totalIn, BigDecimal totalOut,
            int lineCount, int newLines, List<String> warnings, Instant uploadedAt, String uploadedBy, Map<String, Integer> byStatus) {}

    public record Suggestion(String kind, List<UUID> ids, String category, String label, int score, List<String> reasons) {}

    public record MatchView(UUID id, String kind, UUID targetId, String label, BigDecimal amount, String how, String paymentStatus) {}

    public record LineView(UUID id, UUID statementId, int lineNumber, LocalDate date, String narration, String reference, String direction,
            BigDecimal amount, BigDecimal balance, String status, String category, String note, Suggestion suggestion,
            List<MatchView> matches, String decidedBy, Instant decidedAt) {}

    public record OpenRecord(String kind, UUID id, LocalDate date, String member, BigDecimal amount, String direction, String label,
            String status) {}

    public record Overview(boolean canEdit, List<StatementSummary> statements, Map<String, Integer> lineCounts,
            Map<String, BigDecimal> lineAmounts, int recordsNotOnStatement, BalanceCheck balance) {}

    public record BalanceCheck(LocalDate asAt, BigDecimal statementBalance, BigDecimal appCash, BigDecimal difference) {}

    public record StatementDetail(boolean canEdit, StatementSummary statement, List<LineView> lines, List<OpenRecord> notOnStatement,
            BalanceCheck balance) {}

    public record LinkRequest(List<UUID> paymentIds, List<UUID> ledgerEntryIds) {}

    public record ExplainRequest(String category, String note) {}

    public record VerifyResult(UUID paymentId, String outcome, String message) {}

    // ------------------------------------------------------------------ reading

    @Transactional(readOnly = true)
    public Overview overview(UUID cooperativeId) {
        UserPrincipal actor = requireView(cooperativeId);
        List<StatementSummary> statements = jdbc.query(STATEMENT_SQL + " where s.cooperative_id = ? order by s.period_to desc nulls last, s.created_at desc",
                (rs, i) -> summary(rs), cooperativeId);
        Map<String, Integer> counts = new LinkedHashMap<>();
        Map<String, BigDecimal> amounts = new LinkedHashMap<>();
        jdbc.query("select status, count(*), coalesce(sum(amount),0) from bank_statement_lines where cooperative_id = ? group by status",
                rs -> {
                    counts.put(rs.getString(1), rs.getInt(2));
                    amounts.put(rs.getString(1), rs.getBigDecimal(3));
                }, cooperativeId);
        LocalDate from = statements.stream().map(StatementSummary::periodFrom).filter(java.util.Objects::nonNull).min(LocalDate::compareTo).orElse(null);
        LocalDate to = statements.stream().map(StatementSummary::periodTo).filter(java.util.Objects::nonNull).max(LocalDate::compareTo).orElse(null);
        int open = from == null ? 0 : notOnStatement(cooperativeId, from, to).size();
        StatementSummary latest = statements.stream().filter(s -> s.periodTo() != null && s.closingBalance() != null)
                .max(java.util.Comparator.comparing(StatementSummary::periodTo)).orElse(null);
        return new Overview(canEdit(actor), statements, counts, amounts, open, latest == null ? null : balance(cooperativeId, latest));
    }

    @Transactional(readOnly = true)
    public StatementDetail statement(UUID cooperativeId, UUID statementId) {
        UserPrincipal actor = requireView(cooperativeId);
        StatementSummary s = requireStatement(cooperativeId, statementId);
        List<LineView> lines = lines(cooperativeId, "l.statement_id = ?", statementId);
        LocalDate from = s.periodFrom();
        LocalDate to = s.periodTo();
        return new StatementDetail(canEdit(actor), s, lines, from == null ? List.of() : notOnStatement(cooperativeId, from, to),
                s.closingBalance() == null || to == null ? null : balance(cooperativeId, s));
    }

    /** Lines of every statement in one status (for the "to do" lists). */
    @Transactional(readOnly = true)
    public List<LineView> linesByStatus(UUID cooperativeId, String status) {
        requireView(cooperativeId);
        return lines(cooperativeId, "l.status = ?", status.toUpperCase(Locale.ROOT));
    }

    /** Records that could explain a line: same direction, open, nearest amounts and dates first. */
    @Transactional(readOnly = true)
    public List<StatementMatcher.Candidate> candidates(UUID cooperativeId, UUID lineId) {
        requireView(cooperativeId);
        Map<String, Object> line = requireLine(cooperativeId, lineId);
        LocalDate date = day(line.get("transaction_date"));
        boolean in = "IN".equals(line.get("direction"));
        List<StatementMatcher.Candidate> all = new ArrayList<>(in ? paymentCandidates(cooperativeId, date) : List.of());
        all.addAll(ledgerCandidates(cooperativeId, date, in));
        BigDecimal amount = new BigDecimal(line.get("amount").toString());
        all.sort(java.util.Comparator.comparing((StatementMatcher.Candidate c) -> c.amount().subtract(amount).abs())
                .thenComparing(c -> c.date() == null ? Long.MAX_VALUE : Math.abs(c.date().toEpochDay() - date.toEpochDay())));
        return all.size() > 50 ? all.subList(0, 50) : all;
    }

    // ------------------------------------------------------------------ upload

    @Transactional
    public StatementSummary upload(UUID cooperativeId, MultipartFile file, String source, String accountLabel, HttpServletRequest http) {
        UserPrincipal actor = requireEdit(cooperativeId);
        if (file == null || file.isEmpty()) {
            throw ValidationException.coded("FILE_REQUIRED", "Choose the statement file");
        }
        if (file.getSize() > MAX_FILE_BYTES) {
            throw ValidationException.coded("FILE_TOO_LARGE", "The statement must be 10 MB or smaller");
        }
        String src = "MOBILE_MONEY".equalsIgnoreCase(source) ? "MOBILE_MONEY" : "BANK";
        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException e) {
            throw ValidationException.coded("FILE_UNREADABLE", "The file could not be read");
        }
        StatementParser.Parsed parsed;
        try {
            parsed = StatementParser.parse(file.getOriginalFilename(), bytes);
        } catch (IllegalArgumentException e) {
            throw new BusinessException("STATEMENT_UNREADABLE", e.getMessage());
        }
        if (parsed.lines().isEmpty()) {
            throw new BusinessException("STATEMENT_EMPTY", "No statement lines were found in this file. "
                    + String.join(" ", parsed.warnings()));
        }
        String hash = sha256(bytes);
        Long same = jdbc.queryForObject("select count(*) from bank_statements where cooperative_id = ? and file_hash = ?", Long.class,
                cooperativeId, hash);
        if (same != null && same > 0) {
            throw new BusinessException("STATEMENT_ALREADY_UPLOADED", "This statement file was already uploaded");
        }
        String key = "statements/" + cooperativeId + "/" + UUID.randomUUID();
        try {
            fileStorageService.store(key, new ByteArrayInputStream(bytes), bytes.length,
                    file.getContentType() == null ? "application/octet-stream" : file.getContentType());
        } catch (RuntimeException e) {
            key = null;
        }
        UUID statementId = UUID.randomUUID();
        LocalDate from = parsed.lines().stream().map(StatementParser.Line::date).min(LocalDate::compareTo).orElse(null);
        LocalDate to = parsed.lines().stream().map(StatementParser.Line::date).max(LocalDate::compareTo).orElse(null);
        BigDecimal totalIn = parsed.lines().stream().filter(StatementParser.Line::in).map(StatementParser.Line::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totalOut = parsed.lines().stream().filter(l -> !l.in()).map(StatementParser.Line::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
        String label = accountLabel != null && !accountLabel.isBlank() ? accountLabel.trim() : parsed.account();
        jdbc.update("insert into bank_statements (id, cooperative_id, source, account_label, original_filename, file_hash, storage_key, period_from,"
                        + " period_to, opening_balance, closing_balance, total_in, total_out, line_count, new_lines, parse_warnings, uploaded_by, created_at)"
                        + " values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0, ?, ?, now())",
                statementId, cooperativeId, src, cut(label, 128), cut(file.getOriginalFilename(), 500), hash, key,
                from == null ? null : Date.valueOf(from), to == null ? null : Date.valueOf(to), parsed.openingBalance(), parsed.closingBalance(),
                totalIn, totalOut, parsed.lines().size(), json(parsed.warnings()), actor.getId());
        // A line already uploaded with an overlapping statement is kept once (same date, direction, amount, balance and
        // narration; a repeated identical line in one statement is told apart by its position among its twins).
        Map<String, Integer> twins = new HashMap<>();
        List<UUID> fresh = new ArrayList<>();
        for (StatementParser.Line l : parsed.lines()) {
            String base = l.date() + "|" + (l.in() ? "IN" : "OUT") + "|" + l.amount().stripTrailingZeros().toPlainString() + "|"
                    + (l.balance() == null ? "" : l.balance().stripTrailingZeros().toPlainString()) + "|"
                    + StatementMatcher.alnum(l.narration());
            int n = twins.merge(base, 1, Integer::sum);
            String fingerprint = sha256((src + "|" + base + "|" + n).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            Long exists = jdbc.queryForObject("select count(*) from bank_statement_lines where cooperative_id = ? and fingerprint = ?",
                    Long.class, cooperativeId, fingerprint);
            if (exists != null && exists > 0) {
                continue;
            }
            UUID id = UUID.randomUUID();
            jdbc.update("insert into bank_statement_lines (id, cooperative_id, statement_id, line_number, transaction_date, value_date, narration,"
                            + " reference, direction, amount, balance, fingerprint, status, created_at) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'UNMATCHED', now())",
                    id, cooperativeId, statementId, l.number(), Date.valueOf(l.date()), l.valueDate() == null ? null : Date.valueOf(l.valueDate()),
                    l.narration() == null || l.narration().isBlank() ? "-" : l.narration(), cut(l.reference(), 128), l.in() ? "IN" : "OUT",
                    l.amount(), l.balance(), fingerprint);
            fresh.add(id);
        }
        jdbc.update("update bank_statements set new_lines = ? where id = ?", fresh.size(), statementId);
        match(cooperativeId, fresh);
        auditService.record(actor.getId(), cooperativeId, AuditableAction.IMPORT, "BankStatement", statementId, null,
                json(Map.of("lines", parsed.lines().size(), "newLines", fresh.size(), "source", src)), clientIp(http),
                http == null ? null : http.getHeader("User-Agent"));
        return requireStatement(cooperativeId, statementId);
    }

    // ------------------------------------------------------------------ matching

    /** Matches lines again (for example after members submitted more payments). Officer decisions are never changed. */
    @Transactional
    public Map<String, Integer> rematch(UUID cooperativeId) {
        requireEdit(cooperativeId);
        List<UUID> open = jdbc.queryForList("select id from bank_statement_lines where cooperative_id = ? and status in ('UNMATCHED','SUGGESTED')",
                UUID.class, cooperativeId);
        match(cooperativeId, open);
        Map<String, Integer> counts = new LinkedHashMap<>();
        jdbc.query("select status, count(*) from bank_statement_lines where cooperative_id = ? group by status",
                rs -> {
                    counts.put(rs.getString(1), rs.getInt(2));
                }, cooperativeId);
        return counts;
    }

    private void match(UUID cooperativeId, List<UUID> lineIds) {
        if (lineIds.isEmpty()) {
            return;
        }
        Set<UUID> taken = new HashSet<>();
        for (UUID lineId : lineIds) {
            Map<String, Object> line = jdbc.queryForMap("select * from bank_statement_lines where id = ?", lineId);
            LocalDate date = day(line.get("transaction_date"));
            boolean in = "IN".equals(line.get("direction"));
            BigDecimal amount = new BigDecimal(line.get("amount").toString());
            String narration = (String) line.get("narration");
            List<StatementMatcher.Candidate> candidates = new ArrayList<>();
            if (in) {
                candidates.addAll(paymentCandidates(cooperativeId, date));
            }
            candidates.addAll(ledgerCandidates(cooperativeId, date, in));
            candidates.removeIf(c -> taken.contains(c.id()));
            StatementMatcher.Verdict v = StatementMatcher.decide(narration, (String) line.get("reference"), amount, date, candidates);
            if ("MATCHED".equals(v.status())) {
                link(cooperativeId, lineId, List.of(v.best().candidate()), "AUTOMATIC", null);
                taken.add(v.best().candidate().id());
                jdbc.update("update bank_statement_lines set status = 'MATCHED', suggestion = ?, suggestion_score = ? where id = ?",
                        json(suggestionOf(v.best())), v.best().score(), lineId);
            } else if ("SUGGESTED".equals(v.status())) {
                jdbc.update("update bank_statement_lines set status = 'SUGGESTED', suggestion = ?, suggestion_score = ? where id = ?",
                        json(suggestionOf(v.best())), v.best().score(), lineId);
            } else if (!in && StatementMatcher.looksLikeBankCharge(narration)) {
                jdbc.update("update bank_statement_lines set status = 'SUGGESTED', suggestion = ?, suggestion_score = 60 where id = ?",
                        json(new Suggestion("EXPLAIN", List.of(), "BANK_CHARGE", "Bank charge", 60, List.of("LOOKS_LIKE_A_BANK_CHARGE"))), lineId);
            } else {
                jdbc.update("update bank_statement_lines set status = 'UNMATCHED', suggestion = null, suggestion_score = null where id = ?", lineId);
            }
        }
    }

    private static Suggestion suggestionOf(StatementMatcher.Scored s) {
        return new Suggestion(s.candidate().kind(), List.of(s.candidate().id()), null, s.candidate().label(), s.score(), s.reasons());
    }

    /** Member payments (not cash, not refused) not yet reconciled, within a month of the date. */
    private List<StatementMatcher.Candidate> paymentCandidates(UUID cooperativeId, LocalDate date) {
        return jdbc.query("select p.id, p.member_user_id, u.first_name, u.last_name, u.phone, p.amount, p.payment_date, p.transaction_reference,"
                        + " p.status from member_payments p join users u on u.id = p.member_user_id where p.cooperative_id = ?"
                        + " and p.payment_method <> 'CASH' and p.status not in ('REJECTED','REVERSED')"
                        + " and p.payment_date between ? and ?"
                        + " and not exists (select 1 from bank_statement_matches m where m.payment_id = p.id)",
                (rs, i) -> {
                    String name = (nz(rs.getString(3)) + " " + nz(rs.getString(4))).trim();
                    LocalDate d = rs.getDate(7).toLocalDate();
                    return new StatementMatcher.Candidate("PAYMENT", rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), name,
                            rs.getString(5), rs.getBigDecimal(6), d, rs.getString(8),
                            name + ", payment of " + d + (rs.getString(8) == null ? "" : " (" + rs.getString(8) + ")") + ", " + rs.getString(9));
                },
                cooperativeId, Date.valueOf(date.minusDays(31)), Date.valueOf(date.plusDays(31)));
    }

    /** Ledger entries that moved money in that direction and are not part of a reconciled payment, within a month. */
    private List<StatementMatcher.Candidate> ledgerCandidates(UUID cooperativeId, LocalDate date, boolean in) {
        Set<String> types = in ? IN_TYPES : OUT_TYPES;
        String typeList = String.join(",", types.stream().map(t -> "'" + t + "'").toList());
        return jdbc.query("select e.id, e.member_user_id, u.first_name, u.last_name, u.phone, " + (in ? "e.credit_amount" : "e.debit_amount")
                        + ", e.transaction_date, e.reference, e.transaction_type, e.description from financial_ledger e"
                        + " left join users u on u.id = e.member_user_id where e.cooperative_id = ? and e.status = 'APPROVED'"
                        + " and " + (in ? "e.credit_amount" : "e.debit_amount") + " > 0 and e.transaction_type in (" + typeList + ")"
                        + " and e.transaction_date between ? and ?"
                        + " and not exists (select 1 from bank_statement_matches m where m.ledger_entry_id = e.id)"
                        + " and not exists (select 1 from member_payment_postings pp where pp.ledger_entry_id = e.id)",
                (rs, i) -> {
                    String name = (nz(rs.getString(3)) + " " + nz(rs.getString(4))).trim();
                    LocalDate d = rs.getDate(7).toLocalDate();
                    String type = rs.getString(9);
                    return new StatementMatcher.Candidate("LEDGER", rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
                            name.isEmpty() ? null : name, rs.getString(5), rs.getBigDecimal(6), d, rs.getString(8),
                            label(type) + (name.isEmpty() ? "" : ", " + name) + ", " + d
                                    + (rs.getString(10) == null ? "" : " (" + cut(rs.getString(10), 80) + ")"));
                },
                cooperativeId, Date.valueOf(date.minusDays(31)), Date.valueOf(date.plusDays(31)));
    }

    // ------------------------------------------------------------------ officer decisions

    @Transactional
    public LineView confirm(UUID cooperativeId, UUID lineId, HttpServletRequest http) {
        UserPrincipal actor = requireEdit(cooperativeId);
        Map<String, Object> line = lockLine(cooperativeId, lineId);
        Suggestion s = readSuggestion((String) line.get("suggestion"));
        if (!"SUGGESTED".equals(line.get("status")) || s == null) {
            throw new BusinessException("NOTHING_TO_CONFIRM", "This line has no suggestion to confirm");
        }
        if ("EXPLAIN".equals(s.kind())) {
            return explain(cooperativeId, lineId, new ExplainRequest(s.category(), s.label()), http);
        }
        return linkLine(cooperativeId, lineId, "PAYMENT".equals(s.kind()) ? new LinkRequest(s.ids(), List.of())
                : new LinkRequest(List.of(), s.ids()), http, actor);
    }

    /** Confirms every suggestion of a statement (or of all statements) in one go. */
    @Transactional
    public int confirmAll(UUID cooperativeId, UUID statementId, HttpServletRequest http) {
        requireEdit(cooperativeId);
        List<UUID> ids = statementId == null
                ? jdbc.queryForList("select id from bank_statement_lines where cooperative_id = ? and status = 'SUGGESTED'", UUID.class, cooperativeId)
                : jdbc.queryForList("select id from bank_statement_lines where cooperative_id = ? and statement_id = ? and status = 'SUGGESTED'",
                        UUID.class, cooperativeId, statementId);
        int done = 0;
        for (UUID id : ids) {
            try {
                confirm(cooperativeId, id, http);
                done++;
            } catch (BusinessException e) {
                // a suggestion whose record was meanwhile reconciled elsewhere stays for an officer
            }
        }
        return done;
    }

    @Transactional
    public LineView link(UUID cooperativeId, UUID lineId, LinkRequest request, HttpServletRequest http) {
        UserPrincipal actor = requireEdit(cooperativeId);
        return linkLine(cooperativeId, lineId, request, http, actor);
    }

    private LineView linkLine(UUID cooperativeId, UUID lineId, LinkRequest request, HttpServletRequest http, UserPrincipal actor) {
        Map<String, Object> line = lockLine(cooperativeId, lineId);
        if (Set.of("MATCHED", "CONFIRMED", "EXPLAINED").contains((String) line.get("status"))) {
            throw new BusinessException("LINE_ALREADY_RECONCILED", "This line is already reconciled; undo it first");
        }
        boolean in = "IN".equals(line.get("direction"));
        List<StatementMatcher.Candidate> targets = new ArrayList<>();
        for (UUID id : request.paymentIds() == null ? List.<UUID>of() : request.paymentIds()) {
            if (!in) {
                throw new BusinessException("WRONG_DIRECTION", "A member payment is money in; this line is money out");
            }
            List<Map<String, Object>> p = jdbc.queryForList("select id, amount from member_payments where id = ? and cooperative_id = ?"
                    + " and status not in ('REJECTED','REVERSED')", id, cooperativeId);
            if (p.isEmpty()) {
                throw new ResourceNotFoundException("Payment", id);
            }
            targets.add(new StatementMatcher.Candidate("PAYMENT", id, null, null, null, new BigDecimal(p.get(0).get("amount").toString()),
                    null, null, null));
        }
        for (UUID id : request.ledgerEntryIds() == null ? List.<UUID>of() : request.ledgerEntryIds()) {
            List<Map<String, Object>> e = jdbc.queryForList("select id, credit_amount, debit_amount from financial_ledger where id = ? and cooperative_id = ?"
                    + " and status = 'APPROVED'", id, cooperativeId);
            if (e.isEmpty()) {
                throw new ResourceNotFoundException("Ledger entry", id);
            }
            BigDecimal value = new BigDecimal(e.get(0).get(in ? "credit_amount" : "debit_amount").toString());
            if (value.signum() <= 0) {
                throw new BusinessException("WRONG_DIRECTION", "That ledger entry did not move money in this direction");
            }
            targets.add(new StatementMatcher.Candidate("LEDGER", id, null, null, null, value, null, null, null));
        }
        if (targets.isEmpty()) {
            throw new BusinessException("NOTHING_TO_LINK", "Choose the payments or ledger entries this line pays for");
        }
        BigDecimal sum = targets.stream().map(StatementMatcher.Candidate::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal amount = new BigDecimal(line.get("amount").toString());
        if (sum.compareTo(amount) != 0) {
            throw new BusinessException("AMOUNTS_DIFFER", "The records chosen add up to " + sum.stripTrailingZeros().toPlainString()
                    + " but the line is " + amount.stripTrailingZeros().toPlainString());
        }
        link(cooperativeId, lineId, targets, "OFFICER", actor.getId());
        jdbc.update("update bank_statement_lines set status = 'CONFIRMED', decided_by = ?, decided_at = now() where id = ?", actor.getId(), lineId);
        audit(actor, cooperativeId, lineId, "LINK", targets.size() + " record(s)", http);
        return lines(cooperativeId, "l.id = ?", lineId).get(0);
    }

    private void link(UUID cooperativeId, UUID lineId, List<StatementMatcher.Candidate> targets, String how, UUID actorId) {
        for (StatementMatcher.Candidate t : targets) {
            Long used = jdbc.queryForObject("select count(*) from bank_statement_matches where " + ("PAYMENT".equals(t.kind()) ? "payment_id" : "ledger_entry_id")
                    + " = ?", Long.class, t.id());
            if (used != null && used > 0) {
                throw new BusinessException("RECORD_ALREADY_RECONCILED", "One of the records is already reconciled with another statement line");
            }
            jdbc.update("insert into bank_statement_matches (id, cooperative_id, line_id, payment_id, ledger_entry_id, amount, how, matched_by, matched_at)"
                            + " values (?, ?, ?, ?, ?, ?, ?, ?, now())",
                    UUID.randomUUID(), cooperativeId, lineId, "PAYMENT".equals(t.kind()) ? t.id() : null, "LEDGER".equals(t.kind()) ? t.id() : null,
                    t.amount(), how, actorId);
        }
    }

    @Transactional
    public LineView explain(UUID cooperativeId, UUID lineId, ExplainRequest request, HttpServletRequest http) {
        UserPrincipal actor = requireEdit(cooperativeId);
        Map<String, Object> line = lockLine(cooperativeId, lineId);
        if (Set.of("MATCHED", "CONFIRMED", "EXPLAINED").contains((String) line.get("status"))) {
            throw new BusinessException("LINE_ALREADY_RECONCILED", "This line is already reconciled; undo it first");
        }
        String category = request == null || request.category() == null ? "" : request.category().trim().toUpperCase(Locale.ROOT);
        if (!CATEGORIES.contains(category)) {
            throw new BusinessException("INVALID_CATEGORY", "Choose what this line is");
        }
        String note = request.note() == null ? "" : request.note().trim();
        if (Set.of("OTHER", "NOT_SCHEME_MONEY").contains(category) && note.length() < 5) {
            throw new BusinessException("NOTE_REQUIRED", "Explain this line in a few words");
        }
        jdbc.update("update bank_statement_lines set status = 'EXPLAINED', category = ?, note = ?, decided_by = ?, decided_at = now() where id = ?",
                category, cut(note, 500), actor.getId(), lineId);
        audit(actor, cooperativeId, lineId, "EXPLAIN", category, http);
        return lines(cooperativeId, "l.id = ?", lineId).get(0);
    }

    @Transactional
    public LineView undo(UUID cooperativeId, UUID lineId, HttpServletRequest http) {
        UserPrincipal actor = requireEdit(cooperativeId);
        lockLine(cooperativeId, lineId);
        Long verified = jdbc.queryForObject("select count(*) from bank_statement_matches m join member_payments p on p.id = m.payment_id"
                + " where m.line_id = ? and p.status in ('VERIFIED','POSTED') and p.verification_reference = ?", Long.class, lineId,
                StatementVerification.reference(lineId));
        if (verified != null && verified > 0) {
            throw new BusinessException("LINE_USED_AS_EVIDENCE",
                    "A payment was verified with this statement line as its evidence; the link cannot be undone");
        }
        jdbc.update("delete from bank_statement_matches where line_id = ?", lineId);
        jdbc.update("update bank_statement_lines set status = 'UNMATCHED', category = null, note = null, decided_by = ?, decided_at = now()"
                + " where id = ?", actor.getId(), lineId);
        match(cooperativeId, List.of(lineId));
        audit(actor, cooperativeId, lineId, "UNDO", null, http);
        return lines(cooperativeId, "l.id = ?", lineId).get(0);
    }

    /**
     * Verifies the member payments reconciled with statement lines, with the line as the confirmation evidence (bank
     * reconciled). The usual rules hold: the person who submitted a payment cannot verify it, and posting stays a separate
     * step. Returns what happened to each payment.
     */
    public List<VerifyResult> verifyReconciled(UUID cooperativeId, UUID statementId) {
        requireEdit(cooperativeId);
        List<Map<String, Object>> rows = jdbc.queryForList(
                "select m.payment_id, m.line_id from bank_statement_matches m join bank_statement_lines l on l.id = m.line_id"
                        + " join member_payments p on p.id = m.payment_id where m.cooperative_id = ? and l.status in ('MATCHED','CONFIRMED')"
                        + " and p.status in ('SUBMITTED','UNDER_REVIEW')" + (statementId == null ? "" : " and l.statement_id = ?"),
                statementId == null ? new Object[] {cooperativeId} : new Object[] {cooperativeId, statementId});
        List<VerifyResult> results = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            UUID payment = (UUID) r.get("payment_id");
            try {
                verification.verify(cooperativeId, payment, (UUID) r.get("line_id"));
                results.add(new VerifyResult(payment, "VERIFIED", null));
            } catch (RuntimeException e) {
                results.add(new VerifyResult(payment, "NOT_VERIFIED", e.getMessage()));
            }
        }
        return results;
    }

    // ------------------------------------------------------------------ helpers

    private List<OpenRecord> notOnStatement(UUID cooperativeId, LocalDate from, LocalDate to) {
        List<OpenRecord> out = new ArrayList<>(jdbc.query(
                "select p.id, p.payment_date, u.first_name, u.last_name, p.amount, p.payment_method, p.transaction_reference, p.status"
                        + " from member_payments p join users u on u.id = p.member_user_id where p.cooperative_id = ? and p.payment_method <> 'CASH'"
                        + " and p.status not in ('REJECTED','REVERSED') and p.payment_date between ? and ?"
                        + " and not exists (select 1 from bank_statement_matches m where m.payment_id = p.id) order by p.payment_date",
                (rs, i) -> new OpenRecord("PAYMENT", rs.getObject(1, UUID.class), rs.getDate(2).toLocalDate(),
                        (nz(rs.getString(3)) + " " + nz(rs.getString(4))).trim(), rs.getBigDecimal(5), "IN",
                        rs.getString(6) + (rs.getString(7) == null ? "" : " " + rs.getString(7)), rs.getString(8)),
                cooperativeId, Date.valueOf(from), Date.valueOf(to)));
        String types = String.join(",", java.util.stream.Stream.concat(IN_TYPES.stream(), OUT_TYPES.stream()).map(t -> "'" + t + "'").toList());
        out.addAll(jdbc.query("select e.id, e.transaction_date, u.first_name, u.last_name, e.credit_amount, e.debit_amount, e.transaction_type, e.description"
                        + " from financial_ledger e left join users u on u.id = e.member_user_id where e.cooperative_id = ? and e.status = 'APPROVED'"
                        + " and e.transaction_type in (" + types + ") and e.transaction_date between ? and ?"
                        + " and not exists (select 1 from bank_statement_matches m where m.ledger_entry_id = e.id)"
                        + " and not exists (select 1 from member_payment_postings pp where pp.ledger_entry_id = e.id) order by e.transaction_date",
                (rs, i) -> {
                    boolean in = rs.getBigDecimal(5) != null && rs.getBigDecimal(5).signum() > 0;
                    return new OpenRecord("LEDGER", rs.getObject(1, UUID.class), rs.getDate(2).toLocalDate(),
                            (nz(rs.getString(3)) + " " + nz(rs.getString(4))).trim(), in ? rs.getBigDecimal(5) : rs.getBigDecimal(6),
                            in ? "IN" : "OUT", label(rs.getString(7)) + (rs.getString(8) == null ? "" : ": " + cut(rs.getString(8), 80)), null);
                },
                cooperativeId, Date.valueOf(from), Date.valueOf(to)));
        return out;
    }

    private BalanceCheck balance(UUID cooperativeId, StatementSummary s) {
        BigDecimal app = positionService.compute(cooperativeId, s.periodTo()).availableFunds();
        return new BalanceCheck(s.periodTo(), s.closingBalance(), app, s.closingBalance().subtract(app));
    }

    private List<LineView> lines(UUID cooperativeId, String where, Object arg) {
        Map<UUID, List<MatchView>> matches = new HashMap<>();
        jdbc.query("select m.id, m.line_id, m.payment_id, m.ledger_entry_id, m.amount, m.how, p.status, pu.first_name, pu.last_name, p.payment_date,"
                        + " e.transaction_type, e.transaction_date, eu.first_name, eu.last_name"
                        + " from bank_statement_matches m join bank_statement_lines l on l.id = m.line_id"
                        + " left join member_payments p on p.id = m.payment_id left join users pu on pu.id = p.member_user_id"
                        + " left join financial_ledger e on e.id = m.ledger_entry_id left join users eu on eu.id = e.member_user_id"
                        + " where m.cooperative_id = ? and " + where,
                rs -> {
                    boolean payment = rs.getObject(3) != null;
                    String label = payment
                            ? (nz(rs.getString(8)) + " " + nz(rs.getString(9))).trim() + ", payment of " + rs.getDate(10)
                            : label(rs.getString(11)) + ", " + rs.getDate(12)
                                    + ((rs.getString(13) == null) ? "" : ", " + (nz(rs.getString(13)) + " " + nz(rs.getString(14))).trim());
                    matches.computeIfAbsent(rs.getObject(2, UUID.class), k -> new ArrayList<>()).add(new MatchView(rs.getObject(1, UUID.class),
                            payment ? "PAYMENT" : "LEDGER", payment ? rs.getObject(3, UUID.class) : rs.getObject(4, UUID.class), label,
                            rs.getBigDecimal(5), rs.getString(6), rs.getString(7)));
                }, cooperativeId, arg);
        return jdbc.query("select l.*, trim(coalesce(u.first_name,'') || ' ' || coalesce(u.last_name,'')) as decider from bank_statement_lines l"
                        + " left join users u on u.id = l.decided_by where l.cooperative_id = ? and " + where
                        + " order by l.transaction_date, l.line_number",
                (rs, i) -> new LineView(rs.getObject("id", UUID.class), rs.getObject("statement_id", UUID.class), rs.getInt("line_number"),
                        rs.getDate("transaction_date").toLocalDate(), rs.getString("narration"), rs.getString("reference"), rs.getString("direction"),
                        rs.getBigDecimal("amount"), rs.getBigDecimal("balance"), rs.getString("status"), rs.getString("category"),
                        rs.getString("note"), readSuggestion(rs.getString("suggestion")),
                        matches.getOrDefault(rs.getObject("id", UUID.class), List.of()),
                        rs.getString("decider") == null || rs.getString("decider").isBlank() ? null : rs.getString("decider"),
                        rs.getTimestamp("decided_at") == null ? null : rs.getTimestamp("decided_at").toInstant()),
                cooperativeId, arg);
    }

    private static final String STATEMENT_SQL = "select s.*, trim(coalesce(u.first_name,'') || ' ' || coalesce(u.last_name,'')) as uploader from bank_statements s"
            + " left join users u on u.id = s.uploaded_by";

    private StatementSummary summary(java.sql.ResultSet rs) throws java.sql.SQLException {
        UUID id = rs.getObject("id", UUID.class);
        Map<String, Integer> byStatus = new LinkedHashMap<>();
        jdbc.query("select status, count(*) from bank_statement_lines where statement_id = ? group by status",
                r -> {
                    byStatus.put(r.getString(1), r.getInt(2));
                }, id);
        return new StatementSummary(id, rs.getString("source"), rs.getString("account_label"), rs.getString("original_filename"),
                rs.getDate("period_from") == null ? null : rs.getDate("period_from").toLocalDate(),
                rs.getDate("period_to") == null ? null : rs.getDate("period_to").toLocalDate(),
                rs.getBigDecimal("opening_balance"), rs.getBigDecimal("closing_balance"), rs.getBigDecimal("total_in"), rs.getBigDecimal("total_out"),
                rs.getInt("line_count"), rs.getInt("new_lines"), readList(rs.getString("parse_warnings")),
                rs.getTimestamp("created_at").toInstant(), rs.getString("uploader"), byStatus);
    }

    private StatementSummary requireStatement(UUID cooperativeId, UUID statementId) {
        List<StatementSummary> found = jdbc.query(STATEMENT_SQL + " where s.cooperative_id = ? and s.id = ?", (rs, i) -> summary(rs),
                cooperativeId, statementId);
        if (found.isEmpty()) {
            throw new ResourceNotFoundException("Statement", statementId);
        }
        return found.get(0);
    }

    private Map<String, Object> requireLine(UUID cooperativeId, UUID lineId) {
        List<Map<String, Object>> rows = jdbc.queryForList("select * from bank_statement_lines where id = ? and cooperative_id = ?", lineId, cooperativeId);
        if (rows.isEmpty()) {
            throw new ResourceNotFoundException("Statement line", lineId);
        }
        return rows.get(0);
    }

    private Map<String, Object> lockLine(UUID cooperativeId, UUID lineId) {
        List<Map<String, Object>> rows = jdbc.queryForList("select * from bank_statement_lines where id = ? and cooperative_id = ? for update",
                lineId, cooperativeId);
        if (rows.isEmpty()) {
            throw new ResourceNotFoundException("Statement line", lineId);
        }
        return rows.get(0);
    }

    private UserPrincipal requireView(UUID cooperativeId) {
        authorizationService.requireMembership(cooperativeId);
        UserPrincipal p = authorizationService.currentPrincipal();
        if (p.hasRole(CooperativeAuthorizationService.SUPER_ADMIN) || CooperativeOfficerRoles.OFFICER_ROLE_CODES.stream().anyMatch(p::hasRole)) {
            return p;
        }
        throw new ForbiddenException("Reconciliation is for officers");
    }

    private UserPrincipal requireEdit(UUID cooperativeId) {
        UserPrincipal p = requireView(cooperativeId);
        if (!canEdit(p)) {
            throw new ForbiddenException("Only the Accountant, the President or the Vice President can change the reconciliation");
        }
        return p;
    }

    static boolean canEdit(UserPrincipal p) {
        return p.hasRole(CooperativeAuthorizationService.SUPER_ADMIN) || p.hasRole(CooperativeOfficerRoles.ACCOUNTANT)
                || p.hasRole(CooperativeOfficerRoles.PRESIDENT) || p.hasRole(CooperativeOfficerRoles.VICE_PRESIDENT)
                || p.hasRole(CooperativeOfficerRoles.LEGACY_ADMIN);
    }

    private void audit(UserPrincipal actor, UUID cooperativeId, UUID lineId, String what, String detail, HttpServletRequest http) {
        auditService.record(actor.getId(), cooperativeId, AuditableAction.RECONCILIATION_DECISION, "BankStatementLine", lineId, null,
                json(detail == null ? Map.of("action", what) : Map.of("action", what, "detail", detail)), clientIp(http),
                http == null ? null : http.getHeader("User-Agent"));
    }

    private Suggestion readSuggestion(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(json, Suggestion.class);
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    private List<String> readList(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<String>>() {});
        } catch (JsonProcessingException e) {
            return List.of();
        }
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    static String label(String ledgerType) {
        if (ledgerType == null) {
            return "Ledger entry";
        }
        return switch (ledgerType) {
            case "REGULAR_CONTRIBUTION" -> "Contribution";
            case "SPECIAL_CONTRIBUTION" -> "Special contribution";
            case "LOAN_PRINCIPAL_REPAYMENT" -> "Loan repayment";
            case "LOAN_INTEREST_PAYMENT" -> "Loan interest";
            case "FINE_PAYMENT" -> "Fine paid";
            case "SHARE_PURCHASE" -> "Share purchase";
            case "LOAN_DISBURSEMENT" -> "Loan given out";
            case "MEMBER_PAYOUT" -> "Payout";
            case "GENERAL_EXPENSE" -> "Expense";
            case "INVESTMENT_OUTFLOW" -> "Investment";
            case "SOCIAL_DISBURSEMENT" -> "Social fund paid";
            case "SOCIAL_CONTRIBUTION" -> "Social fund contribution";
            default -> ledgerType.charAt(0) + ledgerType.substring(1).toLowerCase(Locale.ROOT).replace('_', ' ');
        };
    }

    static LocalDate day(Object v) {
        if (v instanceof LocalDate d) {
            return d;
        }
        if (v instanceof Date d) {
            return d.toLocalDate();
        }
        if (v instanceof java.sql.Timestamp t) {
            return t.toLocalDateTime().toLocalDate();
        }
        return LocalDate.parse(String.valueOf(v).substring(0, 10));
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String cut(String s, int max) {
        return s == null ? null : s.length() > max ? s.substring(0, max) : s;
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    private static String clientIp(HttpServletRequest request) {
        if (request == null) {
            return null;
        }
        String forwarded = request.getHeader("X-Forwarded-For");
        return forwarded != null && !forwarded.isBlank() ? forwarded.split(",")[0].trim() : request.getRemoteAddr();
    }
}
