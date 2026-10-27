package rw.terimbere.csams.modules.report.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Date;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import rw.terimbere.csams.modules.contribution.entity.Contribution;
import rw.terimbere.csams.modules.contribution.repository.ContributionRepository;
import rw.terimbere.csams.modules.contribution.service.ContributionService;
import rw.terimbere.csams.modules.position.MemberPositionService;
import rw.terimbere.csams.modules.report.dto.ReportExportRequest;
import rw.terimbere.csams.modules.report.dto.ReportSheetData;
import rw.terimbere.csams.modules.report.dto.ReportType;
import rw.terimbere.csams.shared.exceptions.ValidationException;
import rw.terimbere.csams.shared.utilities.MoneyUtils;

/**
 * The member statement and the officer reports of the target design. Every one is a view of the same balances as the
 * app's screens (member positions, contributions, loans, rotation), so figures never disagree between a screen and its
 * export. The look of the export (logo, title, fonts) is the one every report already has.
 */
@Service
@RequiredArgsConstructor
public class StatementReportService {

    private final JdbcTemplate jdbc;
    private final MemberPositionService positionService;
    private final ContributionRepository contributionRepository;

    public List<ReportSheetData> build(ReportType type, UUID cooperativeId, ReportExportRequest request) {
        LocalDate asAt = request.getToDate() == null ? LocalDate.now(ReportTimelineValidator.ZONE) : request.getToDate();
        return switch (type) {
            case MEMBER_STATEMENT -> memberStatement(cooperativeId, request, asAt);
            case RECONCILIATION -> List.of(reconciliation(cooperativeId));
            case COLLECTION_BY_MONTH -> List.of(collectionByMonth(cooperativeId, request, asAt));
            case ROTATION_SCHEDULE -> rotationSchedule(cooperativeId);
            case MEMBER_POSITIONS -> List.of(memberPositions(cooperativeId));
            case ARREARS_AND_PENALTIES -> List.of(arrears(cooperativeId, asAt));
            case LOAN_BOOK -> loanBook(cooperativeId, asAt);
            case INCOME_AND_EXPENSES -> List.of(incomeAndExpenses(cooperativeId, request, asAt));
            case YEAR_END_CLOSE -> yearEndClose(cooperativeId);
            default -> throw new ValidationException("Not a statement report: " + type);
        };
    }

    // ------------------------------------------------------------------ member statement

    private List<ReportSheetData> memberStatement(UUID cooperativeId, ReportExportRequest request, LocalDate asAt) {
        UUID member = request.getMemberUserId();
        if (member == null) {
            throw new ValidationException("Choose the member whose statement to export");
        }
        MemberPositionService.SchemePosition scheme = positionService.compute(cooperativeId);
        MemberPositionService.MemberPosition p = scheme.members().stream()
                .filter(m -> m.memberUserId().equals(member)).findFirst()
                .orElseThrow(() -> new ValidationException("This member has no position in the scheme"));

        List<List<Object>> turnRows = new ArrayList<>();
        int turnsAhead = 0;
        for (Map<String, Object> t : jdbc.queryForList(
                "select t.id, p.period_year, p.period_month, t.share_count, t.payout_amount, t.opening_received_amount, t.opening_received_date,"
                        + " (select coalesce(sum(r.amount), 0) from rotation_payouts r where r.rotation_turn_id = t.id and r.status = 'DISBURSED') as paid,"
                        + " (select max(r.disbursed_at) from rotation_payouts r where r.rotation_turn_id = t.id and r.status = 'DISBURSED') as paid_at"
                        + " from rotation_turns t join rotation_periods p on p.id = t.rotation_period_id join rotation_cycles c on c.id = t.cycle_id"
                        + " where t.cooperative_id = ? and t.member_user_id = ? and c.status = 'ACTIVE' order by p.period_year, p.period_month",
                cooperativeId, member)) {
            BigDecimal amount = money(t.get("payout_amount"));
            BigDecimal received = money(t.get("opening_received_amount")).add(money(t.get("paid")));
            String status = received.compareTo(amount) >= 0 ? "Paid" : received.signum() > 0 ? "Partly paid" : "Upcoming";
            if (received.compareTo(amount) < 0) {
                turnsAhead++;
            }
            Object date = t.get("paid_at") != null ? instant(t.get("paid_at")) : date(t.get("opening_received_date"));
            turnRows.add(cells(month(t.get("period_year"), t.get("period_month")), num(t.get("share_count")), m(amount), m(received), status, date));
        }

        List<List<Object>> summary = new ArrayList<>();
        summary.add(cells("Shares held", p.shares()));
        summary.add(cells("Capital (what you own in the scheme)", p.capital()));
        summary.add(cells("Your share of income", p.incomeShare()));
        summary.add(cells(p.rotationPosition().signum() >= 0
                ? "Rotation: the scheme owes you (paid in, not yet received)"
                : "Rotation: advance you are repaying (received before paying in)", p.rotationPosition()));
        summary.add(cells("Rotation turns still to come", turnsAhead));
        summary.add(cells("Owed to you by the scheme", p.owedToMember()));
        summary.add(cells("Loans outstanding", p.loansOutstanding()));
        summary.add(cells("Fines outstanding", p.finesOutstanding()));
        summary.add(cells("Contributions overdue", p.contributionArrears()));
        summary.add(cells("Net position (capital + income + rotation + owed to you - loans - fines)", p.netPosition()));

        List<ReportSheetData> sheets = new ArrayList<>();
        sheets.add(sheet("Statement of " + p.memberName() + " as at " + asAt, List.of("Item", "Amount"), summary, null));
        sheets.add(sheet("My turn", List.of("Month", "Shares", "Turn amount", "Received", "Status", "Date"), turnRows, null));

        // Month by month: due, paid, when, late days, penalty, split to capital and rotation.
        Map<UUID, BigDecimal> penalties = new HashMap<>();
        jdbc.query("select source_contribution_id, coalesce(sum(total_amount), 0) from fines where cooperative_id = ? and member_user_id = ?"
                        + " and source_contribution_id is not null and status <> 'CANCELLED' group by source_contribution_id",
                rs -> {
                    penalties.put(rs.getObject(1, UUID.class), rs.getBigDecimal(2));
                }, cooperativeId, member);
        YearMonth from = request.getFromDate() == null ? null : YearMonth.from(request.getFromDate());
        YearMonth to = YearMonth.from(asAt);
        List<List<Object>> months = new ArrayList<>();
        BigDecimal[] totals = {BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO};
        List<Contribution> rows = new ArrayList<>(contributionRepository.findByCooperativeIdAndMemberUserIdOrderByYearDescMonthDesc(cooperativeId, member));
        java.util.Collections.reverse(rows);
        for (Contribution c : rows) {
            YearMonth ym = YearMonth.of(c.getYear(), c.getMonth());
            if ((from != null && ym.isBefore(from)) || ym.isAfter(to) || "CANCELLED".equals(String.valueOf(c.getStatus()))) {
                continue;
            }
            var split = ContributionService.ledgerSplit(c);
            BigDecimal retained = split == null ? BigDecimal.ZERO : split.retained();
            BigDecimal rotating = split == null ? BigDecimal.ZERO : split.rotating();
            LocalDate paidOn = c.getPaymentDate();
            LocalDate due = c.getDueDate();
            long late = due == null ? 0 : Math.max(0, ChronoUnit.DAYS.between(due, paidOn != null ? paidOn : asAt));
            if (paidOn == null && (due == null || !due.isBefore(asAt))) {
                late = 0;
            }
            BigDecimal penalty = penalties.getOrDefault(c.getId(), BigDecimal.ZERO);
            months.add(cells(ym.toString(), m(c.getExpectedAmount()), m(c.getPaidAmount()), paidOn == null ? "" : paidOn,
                    due == null ? "" : due, late, m(penalty), m(retained), m(rotating), m(c.getOutstandingAmount())));
            totals[0] = totals[0].add(nz(c.getExpectedAmount()));
            totals[1] = totals[1].add(nz(c.getPaidAmount()));
            totals[2] = totals[2].add(penalty);
            totals[3] = totals[3].add(retained);
            totals[4] = totals[4].add(rotating);
            totals[5] = totals[5].add(nz(c.getOutstandingAmount()));
        }
        sheets.add(sheet("Month by month",
                List.of("Month", "Due", "Paid", "Paid on", "Due date", "Days late", "Penalty", "To capital", "To rotation", "Still owed"),
                months, cells("Total", m(totals[0]), m(totals[1]), null, null, null, m(totals[2]), m(totals[3]), m(totals[4]), m(totals[5]))));

        List<List<Object>> loans = new ArrayList<>();
        for (Map<String, Object> l : jdbc.queryForList(
                "select l.id, l.disbursement_date, l.principal_amount, l.interest_rate_percent, l.term_months, l.total_repaid_principal,"
                        + " l.total_repaid_interest, l.outstanding_principal, l.outstanding_interest, l.status,"
                        + " (select min(i.due_date) from loan_installments i where i.loan_id = l.id and i.status <> 'PAID') as next_due,"
                        + " (select min(i.scheduled_installment_amount) from loan_installments i where i.loan_id = l.id and i.status <> 'PAID'"
                        + "   and i.due_date = (select min(j.due_date) from loan_installments j where j.loan_id = l.id and j.status <> 'PAID')) as next_amount"
                        + " from loans l where l.cooperative_id = ? and l.member_user_id = ? and l.status in ('ACTIVE','OVERDUE','CLOSED','WRITTEN_OFF')"
                        + " order by l.disbursement_date",
                cooperativeId, member)) {
            loans.add(cells(date(l.get("disbursement_date")), m(money(l.get("principal_amount"))), m(money(l.get("interest_rate_percent"))),
                    num(l.get("term_months")), m(money(l.get("total_repaid_principal"))), m(money(l.get("total_repaid_interest"))),
                    m(money(l.get("outstanding_principal")).add(money(l.get("outstanding_interest")))),
                    date(l.get("next_due")), l.get("next_amount") == null ? "" : m(money(l.get("next_amount"))), String.valueOf(l.get("status"))));
        }
        sheets.add(sheet("Loans", List.of("Issued", "Amount", "Rate % per month", "Term (months)", "Principal paid", "Interest paid",
                "Outstanding", "Next due", "Next amount", "Status"), loans, null));
        return sheets;
    }

    // ------------------------------------------------------------------ officer reports

    private ReportSheetData reconciliation(UUID cooperativeId) {
        MemberPositionService.SchemePosition s = positionService.compute(cooperativeId);
        BigDecimal loans = scalar("select coalesce(sum(outstanding_principal), 0) from loans where cooperative_id = ? and status in ('ACTIVE','OVERDUE')", cooperativeId);
        BigDecimal investments = s.actualAssets().subtract(s.availableFunds()).subtract(loans);
        BigDecimal unclassified = s.members().stream().map(MemberPositionService.MemberPosition::unclassifiedPaid).reduce(BigDecimal.ZERO, BigDecimal::add);
        List<List<Object>> rows = new ArrayList<>();
        rows.add(cells("WHAT THE SCHEME HOLDS", null));
        rows.add(cells("Cash available (bank and cash)", s.availableFunds()));
        rows.add(cells("Loans owed to the scheme (principal)", m(loans)));
        rows.add(cells("Investments", m(investments)));
        rows.add(cells("Total held", s.actualAssets()));
        rows.add(cells("WHO IT BELONGS TO", null));
        rows.add(cells("Members' capital", s.totalCapital()));
        rows.add(cells("Income not yet shared out", s.incomePool()));
        rows.add(cells("Rotation money held for members (paid in, not yet paid out)", s.totalRotationPosition()));
        rows.add(cells("Owed to members", s.totalOwedToMembers()));
        rows.add(cells("Payments not yet split between capital and rotation", m(unclassified)));
        rows.add(cells("Total owed to members", s.expectedAssets()));
        rows.add(cells("DIFFERENCE (held - owed): zero when every franc is explained", s.unexplainedDifference()));
        return sheet("Reconciliation as at " + LocalDate.now(ReportTimelineValidator.ZONE), List.of("Item", "Amount"), rows, null);
    }

    private ReportSheetData collectionByMonth(UUID cooperativeId, ReportExportRequest request, LocalDate asAt) {
        YearMonth from = request.getFromDate() == null ? null : YearMonth.from(request.getFromDate());
        YearMonth to = YearMonth.from(asAt);
        Map<String, BigDecimal[]> byMonth = new TreeMap<>();
        Map<String, Integer> members = new HashMap<>();
        jdbc.query("select \"year\", \"month\", expected_amount, paid_amount, outstanding_amount, payment_date, due_date, status from contributions"
                + " where cooperative_id = ? and status <> 'CANCELLED'", rs -> {
                    YearMonth ym = YearMonth.of(rs.getInt(1), rs.getInt(2));
                    if ((from != null && ym.isBefore(from)) || ym.isAfter(to)) {
                        return;
                    }
                    BigDecimal[] t = byMonth.computeIfAbsent(ym.toString(), k -> zeros(7));
                    members.merge(ym.toString(), 1, Integer::sum);
                    BigDecimal paid = nz(rs.getBigDecimal(4));
                    Date paidOn = rs.getDate(6);
                    Date due = rs.getDate(7);
                    t[0] = t[0].add(nz(rs.getBigDecimal(3)));
                    t[1] = t[1].add(paid);
                    if (paid.signum() > 0 && due != null && paidOn != null && paidOn.toLocalDate().isAfter(due.toLocalDate())) {
                        t[3] = t[3].add(paid);
                    } else {
                        t[2] = t[2].add(paid);
                    }
                    if (!"WAIVED".equals(rs.getString(8))) {
                        t[4] = t[4].add(nz(rs.getBigDecimal(5)));
                    }
                }, cooperativeId);
        jdbc.query("select c.\"year\", c.\"month\", coalesce(sum(f.total_amount), 0), coalesce(sum(f.paid_amount), 0) from fines f"
                + " join contributions c on c.id = f.source_contribution_id where f.cooperative_id = ? and f.status <> 'CANCELLED'"
                + " group by c.\"year\", c.\"month\"", rs -> {
                    BigDecimal[] t = byMonth.get(YearMonth.of(rs.getInt(1), rs.getInt(2)).toString());
                    if (t != null) {
                        t[5] = t[5].add(nz(rs.getBigDecimal(3)));
                        t[6] = t[6].add(nz(rs.getBigDecimal(4)));
                    }
                }, cooperativeId);
        List<List<Object>> rows = new ArrayList<>();
        BigDecimal[] sum = zeros(7);
        byMonth.forEach((ym, t) -> {
            rows.add(cells(ym, members.getOrDefault(ym, 0), m(t[0]), m(t[1]), m(t[2]), m(t[3]), m(t[4]), m(t[5]), m(t[6])));
            for (int i = 0; i < 7; i++) {
                sum[i] = sum[i].add(t[i]);
            }
        });
        return sheet("Collection by month", List.of("Month", "Members due", "Due", "Collected", "On time", "Late", "Unpaid",
                "Penalties raised", "Penalties paid"), rows,
                cells("Total", null, m(sum[0]), m(sum[1]), m(sum[2]), m(sum[3]), m(sum[4]), m(sum[5]), m(sum[6])));
    }

    private List<ReportSheetData> rotationSchedule(UUID cooperativeId) {
        List<List<Object>> rows = new ArrayList<>();
        String next = null;
        for (Map<String, Object> t : jdbc.queryForList(
                "select p.period_year, p.period_month, u.first_name, u.last_name, t.share_count, t.payout_amount, t.opening_received_amount,"
                        + " (select coalesce(sum(r.amount), 0) from rotation_payouts r where r.rotation_turn_id = t.id and r.status = 'DISBURSED') as paid,"
                        + " (select max(r.disbursed_at) from rotation_payouts r where r.rotation_turn_id = t.id and r.status = 'DISBURSED') as paid_at,"
                        + " t.opening_received_date"
                        + " from rotation_turns t join rotation_periods p on p.id = t.rotation_period_id join rotation_cycles c on c.id = t.cycle_id"
                        + " join users u on u.id = t.member_user_id where t.cooperative_id = ? and c.status = 'ACTIVE'"
                        + " order by p.period_year, p.period_month, u.first_name, u.last_name",
                cooperativeId)) {
            BigDecimal amount = money(t.get("payout_amount"));
            BigDecimal received = money(t.get("opening_received_amount")).add(money(t.get("paid")));
            String name = (nzs(t.get("first_name")) + " " + nzs(t.get("last_name"))).trim();
            String month = month(t.get("period_year"), t.get("period_month"));
            String status = received.compareTo(amount) >= 0 ? "Paid" : received.signum() > 0 ? "Partly paid" : "Upcoming";
            if (next == null && received.compareTo(amount) < 0) {
                next = month + ": " + name;
            } else if (next != null && next.startsWith(month + ":") && received.compareTo(amount) < 0) {
                next = next + ", " + name;
            }
            Object date = t.get("paid_at") != null ? instant(t.get("paid_at")) : date(t.get("opening_received_date"));
            rows.add(cells(month, name, num(t.get("share_count")), m(amount), m(received), status, date));
        }
        MemberPositionService.SchemePosition s = positionService.compute(cooperativeId);
        List<List<Object>> summary = new ArrayList<>();
        summary.add(cells("Next to be paid", next == null ? "Cycle complete" : next));
        summary.add(cells("Rotation money held for members (pool)", s.totalRotationPosition()));
        return List.of(
                sheet("Rotation summary", List.of("Item", "Value"), summary, null),
                sheet("Rotation schedule", List.of("Month", "Member", "Shares", "Turn amount", "Received", "Status", "Date"), rows, null));
    }

    private ReportSheetData memberPositions(UUID cooperativeId) {
        MemberPositionService.SchemePosition s = positionService.compute(cooperativeId);
        List<List<Object>> rows = new ArrayList<>();
        BigDecimal[] t = zeros(8);
        int shares = 0;
        for (MemberPositionService.MemberPosition p : s.members()) {
            rows.add(cells(p.memberName(), p.shares(), p.capital(), p.incomeShare(), p.rotationPosition(), p.owedToMember(),
                    p.loansOutstanding(), p.finesOutstanding(), p.contributionArrears(), p.netPosition()));
            shares += p.shares();
            BigDecimal[] v = {p.capital(), p.incomeShare(), p.rotationPosition(), p.owedToMember(), p.loansOutstanding(),
                    p.finesOutstanding(), p.contributionArrears(), p.netPosition()};
            for (int i = 0; i < v.length; i++) {
                t[i] = t[i].add(v[i]);
            }
        }
        return sheet("Member positions as at " + LocalDate.now(ReportTimelineValidator.ZONE),
                List.of("Member", "Shares", "Capital", "Income share", "Rotation position", "Owed to member", "Loans", "Fines",
                        "Overdue contributions", "Net position"),
                rows, cells("Total", shares, m(t[0]), m(t[1]), m(t[2]), m(t[3]), m(t[4]), m(t[5]), m(t[6]), m(t[7])));
    }

    private ReportSheetData arrears(UUID cooperativeId, LocalDate asAt) {
        Map<UUID, BigDecimal[]> fines = new HashMap<>();
        jdbc.query("select source_contribution_id, coalesce(sum(total_amount),0), coalesce(sum(paid_amount),0) from fines"
                        + " where cooperative_id = ? and source_contribution_id is not null and status <> 'CANCELLED' group by source_contribution_id",
                rs -> {
                    fines.put(rs.getObject(1, UUID.class), new BigDecimal[] {nz(rs.getBigDecimal(2)), nz(rs.getBigDecimal(3))});
                }, cooperativeId);
        List<List<Object>> rows = new ArrayList<>();
        BigDecimal[] t = zeros(3);
        for (Map<String, Object> c : jdbc.queryForList(
                "select c.id, u.first_name, u.last_name, c.\"year\", c.\"month\", c.due_date, c.outstanding_amount from contributions c"
                        + " join users u on u.id = c.member_user_id where c.cooperative_id = ? and c.status in ('PENDING','PARTIALLY_PAID')"
                        + " and c.outstanding_amount > 0 and c.due_date < ? order by u.first_name, u.last_name, c.\"year\", c.\"month\"",
                cooperativeId, Date.valueOf(asAt))) {
            LocalDate due = (LocalDate) date(c.get("due_date"));
            BigDecimal[] f = fines.getOrDefault((UUID) c.get("id"), zeros(2));
            BigDecimal outstanding = money(c.get("outstanding_amount"));
            rows.add(cells((nzs(c.get("first_name")) + " " + nzs(c.get("last_name"))).trim(), month(c.get("year"), c.get("month")), due,
                    ChronoUnit.DAYS.between(due, asAt), m(outstanding), m(f[0]), m(f[1]), m(f[0].subtract(f[1]))));
            t[0] = t[0].add(outstanding);
            t[1] = t[1].add(f[0]);
            t[2] = t[2].add(f[1]);
        }
        return sheet("Arrears and penalties as at " + asAt,
                List.of("Member", "Month", "Due date", "Days late", "Unpaid", "Penalty raised", "Penalty paid", "Penalty due"),
                rows, cells("Total", null, null, null, m(t[0]), m(t[1]), m(t[2]), m(t[1].subtract(t[2]))));
    }

    private List<ReportSheetData> loanBook(UUID cooperativeId, LocalDate asAt) {
        Map<String, Object> settings = jdbc.queryForList(
                "select max_loan_amount, max_loan_per_share from loan_settings where cooperative_id = ?", cooperativeId)
                .stream().findFirst().orElse(Map.of());
        BigDecimal perShare = settings.get("max_loan_per_share") == null ? null : money(settings.get("max_loan_per_share"));
        BigDecimal maxLoan = settings.get("max_loan_amount") == null ? null : money(settings.get("max_loan_amount"));
        Map<UUID, Object[]> byMember = new LinkedHashMap<>();
        for (Map<String, Object> r : jdbc.queryForList(
                "select m.user_id, u.first_name, u.last_name, coalesce(m.share_count, 0) as shares,"
                        + " coalesce((select sum(l.outstanding_principal) from loans l where l.cooperative_id = m.cooperative_id and l.member_user_id = m.user_id and l.status in ('ACTIVE','OVERDUE')), 0) as principal,"
                        + " coalesce((select sum(l.outstanding_interest) from loans l where l.cooperative_id = m.cooperative_id and l.member_user_id = m.user_id and l.status in ('ACTIVE','OVERDUE')), 0) as interest,"
                        + " coalesce((select sum(i.scheduled_installment_amount - i.principal_paid - i.interest_paid) from loan_installments i join loans l on l.id = i.loan_id"
                        + "   where l.cooperative_id = m.cooperative_id and l.member_user_id = m.user_id and l.status in ('ACTIVE','OVERDUE') and i.status <> 'PAID' and i.due_date < ?), 0) as overdue,"
                        + " coalesce((select sum(l.total_repaid_interest) from loans l where l.cooperative_id = m.cooperative_id and l.member_user_id = m.user_id), 0) as earned"
                        + " from cooperative_memberships m join users u on u.id = m.user_id where m.cooperative_id = ? order by u.first_name, u.last_name",
                Date.valueOf(asAt), cooperativeId)) {
            byMember.put((UUID) r.get("user_id"), new Object[] {
                (nzs(r.get("first_name")) + " " + nzs(r.get("last_name"))).trim(), num(r.get("shares")), money(r.get("principal")),
                money(r.get("interest")), money(r.get("overdue")), money(r.get("earned"))});
        }
        List<List<Object>> rows = new ArrayList<>();
        BigDecimal[] t = zeros(4);
        for (Object[] v : byMember.values()) {
            int shares = (Integer) v[1];
            BigDecimal limit = perShare != null ? perShare.multiply(BigDecimal.valueOf(shares)) : maxLoan;
            BigDecimal principal = (BigDecimal) v[2];
            Object used = limit == null || limit.signum() == 0 ? "" : principal.multiply(BigDecimal.valueOf(100)).divide(limit, 1, RoundingMode.HALF_UP);
            if (principal.signum() == 0 && ((BigDecimal) v[5]).signum() == 0) {
                continue;
            }
            rows.add(cells(v[0], shares, limit == null ? "" : m(limit), m(principal), m((BigDecimal) v[3]), m((BigDecimal) v[4]),
                    m((BigDecimal) v[5]), used));
            t[0] = t[0].add(principal);
            t[1] = t[1].add((BigDecimal) v[3]);
            t[2] = t[2].add((BigDecimal) v[4]);
            t[3] = t[3].add((BigDecimal) v[5]);
        }
        return List.of(sheet("Loan book as at " + asAt,
                List.of("Member", "Shares", "Limit", "Principal outstanding", "Interest outstanding", "Overdue", "Interest earned", "Limit used %"),
                rows, cells("Total", null, null, m(t[0]), m(t[1]), m(t[2]), m(t[3]), null)));
    }

    private ReportSheetData incomeAndExpenses(UUID cooperativeId, ReportExportRequest request, LocalDate asAt) {
        LocalDate from = request.getFromDate() == null ? LocalDate.of(1900, 1, 1) : request.getFromDate();
        Map<String, BigDecimal[]> byType = new LinkedHashMap<>();
        Map<String, String> labels = new LinkedHashMap<>();
        labels.put("LOAN_INTEREST_PAYMENT", "Loan interest");
        labels.put("FINE_PAYMENT", "Fines and penalties paid");
        labels.put("INVESTMENT_PROFIT", "Investment profit");
        labels.put("OTHER_INCOME", "Other income");
        labels.put("GENERAL_EXPENSE", "Expenses and charges");
        labels.put("INTEREST_EXPENSE", "Interest paid");
        jdbc.query("select transaction_type, coalesce(sum(credit_amount),0), coalesce(sum(debit_amount),0) from financial_ledger"
                        + " where cooperative_id = ? and status = 'APPROVED' and transaction_date between ? and ? group by transaction_type",
                rs -> {
                    byType.put(rs.getString(1), new BigDecimal[] {nz(rs.getBigDecimal(2)), nz(rs.getBigDecimal(3))});
                }, cooperativeId, Date.valueOf(from), Date.valueOf(asAt));
        List<List<Object>> rows = new ArrayList<>();
        BigDecimal income = BigDecimal.ZERO;
        BigDecimal expense = BigDecimal.ZERO;
        for (Map.Entry<String, String> e : labels.entrySet()) {
            BigDecimal[] v = byType.getOrDefault(e.getKey(), zeros(2));
            boolean isExpense = e.getKey().endsWith("EXPENSE");
            BigDecimal amount = isExpense ? v[1].subtract(v[0]) : v[0].subtract(v[1]);
            rows.add(cells(e.getValue(), isExpense ? "" : m(amount), isExpense ? m(amount) : ""));
            if (isExpense) {
                expense = expense.add(amount);
            } else {
                income = income.add(amount);
            }
        }
        BigDecimal net = income.subtract(expense);
        Integer shares = jdbc.queryForObject("select coalesce(sum(share_count),0) from cooperative_memberships where cooperative_id = ? and membership_status = 'ACTIVE'",
                Integer.class, cooperativeId);
        rows.add(cells("Net income", m(net), ""));
        rows.add(cells("Shares", shares == null ? 0 : shares, ""));
        rows.add(cells("Net income per share", shares == null || shares == 0 ? "" : net.divide(BigDecimal.valueOf(shares), 2, RoundingMode.HALF_UP), ""));
        return sheet("Income and expenses", List.of("Item", "Income", "Expense"), rows, null);
    }

    private List<ReportSheetData> yearEndClose(UUID cooperativeId) {
        MemberPositionService.SchemePosition s = positionService.compute(cooperativeId);
        BigDecimal value = s.totalShares() == 0 ? BigDecimal.ZERO
                : s.totalCapital().add(s.incomePool()).divide(BigDecimal.valueOf(s.totalShares()), 2, RoundingMode.HALF_UP);
        List<List<Object>> summary = new ArrayList<>();
        summary.add(cells("Members' capital", s.totalCapital()));
        summary.add(cells("Income to share", s.incomePool()));
        summary.add(cells("Shares", s.totalShares()));
        summary.add(cells("Value of one share (capital + income) / shares", value));
        summary.add(cells("Income per share", s.incomePerShare()));
        List<List<Object>> rows = new ArrayList<>();
        BigDecimal[] t = zeros(7);
        for (MemberPositionService.MemberPosition p : s.members()) {
            BigDecimal gross = p.capital().add(p.incomeShare());
            BigDecimal payable = gross.add(p.rotationPosition()).add(p.owedToMember()).subtract(p.loansOutstanding()).subtract(p.finesOutstanding());
            rows.add(cells(p.memberName(), p.shares(), p.capital(), p.incomeShare(), m(gross), p.rotationPosition(),
                    p.loansOutstanding(), p.finesOutstanding(), m(payable)));
            BigDecimal[] v = {p.capital(), p.incomeShare(), gross, p.rotationPosition(), p.loansOutstanding(), p.finesOutstanding(), payable};
            for (int i = 0; i < v.length; i++) {
                t[i] = t[i].add(v[i]);
            }
        }
        return List.of(
                sheet("Year-end close as at " + LocalDate.now(ReportTimelineValidator.ZONE), List.of("Item", "Amount"), summary, null),
                sheet("Share-out by member", List.of("Member", "Shares", "Capital", "Income share", "Capital + income",
                        "Rotation still owed (+) or advanced (-)", "Less loans", "Less fines", "Net payable if shared out now"),
                        rows, cells("Total", s.totalShares(), m(t[0]), m(t[1]), m(t[2]), m(t[3]), m(t[4]), m(t[5]), m(t[6]))));
    }

    // ------------------------------------------------------------------ helpers

    private static ReportSheetData sheet(String name, List<String> headers, List<List<Object>> rows, List<Object> totals) {
        return ReportSheetData.builder().sheetName(name).headers(headers).rows(rows).totalsRow(totals).build();
    }

    private static List<Object> cells(Object... values) {
        return new ArrayList<>(java.util.Arrays.asList(values));
    }

    private BigDecimal scalar(String sql, Object... args) {
        BigDecimal v = jdbc.queryForObject(sql, BigDecimal.class, args);
        return v == null ? BigDecimal.ZERO : v;
    }

    private static BigDecimal[] zeros(int n) {
        BigDecimal[] a = new BigDecimal[n];
        java.util.Arrays.fill(a, BigDecimal.ZERO);
        return a;
    }

    private static String month(Object year, Object month) {
        return YearMonth.of(((Number) year).intValue(), ((Number) month).intValue()).toString();
    }

    private static Integer num(Object v) {
        return v == null ? 0 : ((Number) v).intValue();
    }

    private static Object date(Object v) {
        if (v == null) {
            return "";
        }
        if (v instanceof LocalDate d) {
            return d;
        }
        if (v instanceof Date d) {
            return d.toLocalDate();
        }
        if (v instanceof java.sql.Timestamp ts) {
            return ts.toLocalDateTime().toLocalDate();
        }
        return LocalDate.parse(v.toString().substring(0, 10));
    }

    private static Object instant(Object v) {
        if (v instanceof java.sql.Timestamp ts) {
            return ts.toInstant();
        }
        if (v instanceof java.time.OffsetDateTime o) {
            return o.toInstant();
        }
        if (v instanceof java.time.Instant i) {
            return i;
        }
        return v == null ? "" : v.toString();
    }

    private static BigDecimal money(Object v) {
        return v == null ? BigDecimal.ZERO : new BigDecimal(v.toString());
    }

    private static BigDecimal m(BigDecimal v) {
        return MoneyUtils.scaleForStorage(v == null ? BigDecimal.ZERO : v);
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    private static String nzs(Object v) {
        return v == null ? "" : v.toString();
    }
}
