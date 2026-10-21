package rw.terimbere.csams.testsupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import rw.terimbere.csams.modules.loan.entity.InterestType;
import rw.terimbere.csams.modules.loan.entity.Loan;
import rw.terimbere.csams.modules.loan.entity.LoanStatus;
import rw.terimbere.csams.modules.loan.repository.LoanRepository;
import rw.terimbere.csams.testsupport.SchemeFixtures.Actor;
import rw.terimbere.csams.testsupport.SchemeFixtures.Scheme;

/**
 * One Saving Scheme with a paying member and the officers who review, verify and post their payments, and the calls that
 * drive a payment through its life over the real HTTP API. Used by the financial acceptance tests so each scenario reads
 * as what happens to the money, not as plumbing.
 */
public final class PaymentScenario {

    public static final ZoneId KIGALI = ZoneId.of("Africa/Kigali");
    public static final YearMonth NOW = YearMonth.now(KIGALI);
    public static final YearMonth NEXT = NOW.plusMonths(1);
    private static final String PROVIDER_EVIDENCE =
            "{\"method\":\"PROVIDER_CONFIRMED\",\"basis\":\"Matched against the trusted record\",\"reference\":\"PROV-1\"}";

    private final MockMvc mockMvc;
    private final ObjectMapper objectMapper;
    private final JdbcTemplate jdbc;
    private final LoanRepository loanRepository;
    private final List<Actor> extraMembers = new java.util.ArrayList<>();
    public final SchemeFixtures fixtures;
    public final Scheme scheme;
    public final Actor payer;
    public final Actor reviewer;
    public final Actor verifier;
    public final Actor poster;

    /** @param payerShares shares of the paying member: the monthly obligation is 1,000 per share */
    public PaymentScenario(
            MockMvc mockMvc, ObjectMapper objectMapper, JdbcTemplate jdbc, LoanRepository loanRepository, String model, int payerShares)
            throws Exception {
        this.mockMvc = mockMvc;
        this.objectMapper = objectMapper;
        this.jdbc = jdbc;
        this.loanRepository = loanRepository;
        this.fixtures = new SchemeFixtures(mockMvc, objectMapper, jdbc);
        this.scheme = fixtures.scheme(model);
        this.payer = scheme.memberA();
        this.reviewer = scheme.accountantA();
        this.verifier = scheme.accountantB();
        this.poster = fixtures.register(scheme.id(), "ACCOUNTANT");
        jdbc.update("update cooperative_memberships set share_count = ? where cooperative_id = ? and user_id = ?",
                payerShares, scheme.id(), payer.id());
    }

    public UUID id() {
        return scheme.id();
    }

    public String base() {
        return "/api/v1/cooperatives/" + scheme.id();
    }

    public static String bearer(Actor who) {
        return "Bearer " + who.token();
    }

    // ------------------------------------------------------------------ allocations and payments

    public static String ref() {
        return "TX" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase();
    }

    public static String contribution(YearMonth month, String amount) {
        return "{\"type\":\"CONTRIBUTION\",\"amount\":%s,\"periodYear\":%d,\"periodMonth\":%d}"
                .formatted(amount, month.getYear(), month.getMonthValue());
    }

    public static String loanAlloc(UUID loanId, String amount) {
        return "{\"type\":\"LOAN_REPAYMENT\",\"amount\":%s,\"loanId\":\"%s\"}".formatted(amount, loanId);
    }

    public static String credit(String amount, String purpose, UUID loanId) {
        return "{\"type\":\"MEMBER_CREDIT\",\"amount\":%s,\"creditPurpose\":\"%s\"%s}"
                .formatted(amount, purpose, loanId == null ? "" : ",\"loanId\":\"" + loanId + "\"");
    }

    public static String body(String amount, String... allocations) {
        return "{\"amount\":%s,\"paymentDate\":\"%s\",\"paymentMethod\":\"MTN_MOMO\",\"transactionReference\":\"%s\",\"allocations\":[%s]}"
                .formatted(amount, LocalDate.now(KIGALI), ref(), String.join(",", allocations));
    }

    public MvcResult submitRaw(Actor who, String json) throws Exception {
        return mockMvc.perform(post(base() + "/payments")
                        .header("Authorization", bearer(who))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andReturn();
    }

    /** Submitted by the payer, reviewed, and verified with provider evidence by a different officer. */
    public UUID verified(String amount, String... allocations) throws Exception {
        return verifiedFor(payer, amount, allocations);
    }

    /** As {@link #verified}, for any member of the scheme. */
    public UUID verifiedFor(Actor member, String amount, String... allocations) throws Exception {
        MvcResult result = submitRaw(member, body(amount, allocations));
        assertThat(status(result)).as(result.getResponse().getContentAsString()).isEqualTo(200);
        UUID id = UUID.fromString(data(result).path("id").asText());
        assertThat(status(act(reviewer, id, "/start-review", null))).isEqualTo(200);
        MvcResult verify = act(verifier, id, "/verify", PROVIDER_EVIDENCE);
        assertThat(status(verify)).as(verify.getResponse().getContentAsString()).isEqualTo(200);
        return id;
    }

    /** Verified, then posted by a third officer. */
    public UUID posted(String amount, String... allocations) throws Exception {
        return postedFor(payer, amount, allocations);
    }

    /** As {@link #posted}, for any member of the scheme. */
    public UUID postedFor(Actor member, String amount, String... allocations) throws Exception {
        UUID id = verifiedFor(member, amount, allocations);
        MvcResult posted = act(poster, id, "/post", null);
        assertThat(status(posted)).as(posted.getResponse().getContentAsString()).isEqualTo(200);
        return id;
    }

    public MvcResult act(Actor who, UUID id, String suffix, String json) throws Exception {
        var request = post(base() + "/payments/" + id + suffix).header("Authorization", bearer(who));
        if (json != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(json);
        }
        return mockMvc.perform(request).andReturn();
    }

    public MvcResult getAs(Actor who, String path) throws Exception {
        return mockMvc.perform(get(base() + path).header("Authorization", bearer(who))).andReturn();
    }

    // ------------------------------------------------------------------ reversals

    /** An officer asks for a posted payment to be reversed; returns the reversal request id. */
    public UUID requestReversal(Actor who, UUID payment, String reason) throws Exception {
        MvcResult result = act(who, payment, "/reversals", "{\"reason\":\"" + reason + "\"}");
        assertThat(status(result)).as(result.getResponse().getContentAsString()).isEqualTo(200);
        return UUID.fromString(data(result).path("id").asText());
    }

    /** The fund authority approves (executes) or declines a requested reversal. */
    public MvcResult decide(Actor who, UUID reversal, String action) throws Exception {
        var request = post(base() + "/payments/reversals/" + reversal + "/" + action).header("Authorization", bearer(who));
        if ("decline".equals(action)) {
            request.contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"checked\"}");
        }
        return mockMvc.perform(request).andReturn();
    }

    // ------------------------------------------------------------------ the member's records

    /** Creates the obligation row of a month (paid 0) through the officer grid, like the real workflow does. */
    public void row(YearMonth month) throws Exception {
        Map<String, Object> line = new LinkedHashMap<>();
        line.put("memberUserId", payer.id());
        line.put("paidAmount", BigDecimal.ZERO);
        assertThat(status(mockMvc.perform(put(base() + "/contributions/period?year=" + month.getYear() + "&month=" + month.getMonthValue())
                        .header("Authorization", "Bearer " + fixtures.superAdminToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("lines", List.of(line)))))
                .andReturn())).isEqualTo(200);
    }

    // ------------------------------------------------------------------ more members and rotation

    /** Another member of the scheme holding {@code shares} shares (an obligation of 1,000 per share each month). */
    public Actor addMember(int shares) throws Exception {
        Actor member = fixtures.register(scheme.id(), "MEMBER");
        extraMembers.add(member);
        jdbc.update("update cooperative_memberships set share_count = ? where cooperative_id = ? and user_id = ?",
                shares, scheme.id(), member.id());
        return member;
    }

    /** The paying member followed by every member added with {@link #addMember}. */
    public List<Actor> allMembers() {
        List<Actor> all = new java.util.ArrayList<>();
        all.add(payer);
        all.addAll(extraMembers);
        return all;
    }

    /** Creates the obligation rows of a month for the given members (paid 0), through the officer grid. */
    public void rows(YearMonth month, List<Actor> members) throws Exception {
        List<Map<String, Object>> lines = new java.util.ArrayList<>();
        for (Actor member : members) {
            Map<String, Object> line = new LinkedHashMap<>();
            line.put("memberUserId", member.id());
            line.put("paidAmount", BigDecimal.ZERO);
            lines.add(line);
        }
        assertThat(status(mockMvc.perform(put(base() + "/contributions/period?year=" + month.getYear() + "&month=" + month.getMonthValue())
                        .header("Authorization", "Bearer " + fixtures.superAdminToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("lines", lines))))
                .andReturn())).isEqualTo(200);
    }

    /** Sets the rotating/retained split (basis points) that governs the periods from {@code effective} on. */
    public void configureRotation(YearMonth effective, int rotatingBps, int retainedBps) throws Exception {
        MvcResult result = mockMvc.perform(post(base() + "/rotation/configurations")
                        .header("Authorization", "Bearer " + fixtures.superAdminToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "effectiveYear", effective.getYear(), "effectiveMonth", effective.getMonthValue(),
                                "rotatingBps", rotatingBps, "retainedBps", retainedBps))))
                .andReturn();
        assertThat(status(result)).as(result.getResponse().getContentAsString()).isEqualTo(200);
    }

    /** Creates and activates a rotation cycle starting at {@code start}, one period per recipient, in this order. */
    public void activateCycle(YearMonth start, List<Actor> recipients) throws Exception {
        MvcResult created = mockMvc.perform(post(base() + "/rotation/cycles")
                        .header("Authorization", "Bearer " + fixtures.superAdminToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "startYear", start.getYear(), "startMonth", start.getMonthValue(),
                                "recipientUserIds", recipients.stream().map(Actor::id).toList()))))
                .andReturn();
        assertThat(status(created)).as(created.getResponse().getContentAsString()).isEqualTo(200);
        String cycleId = data(created).path("id").asText();
        MvcResult activated = mockMvc.perform(post(base() + "/rotation/cycles/" + cycleId + "/activate")
                        .header("Authorization", "Bearer " + fixtures.superAdminToken()))
                .andReturn();
        assertThat(status(activated)).as(activated.getResponse().getContentAsString()).isEqualTo(200);
    }

    public UUID period(YearMonth month) {
        return jdbc.queryForObject(
                "select id from rotation_periods where cooperative_id = ? and period_year = ? and period_month = ?",
                UUID.class, scheme.id(), month.getYear(), month.getMonthValue());
    }

    public MvcResult requestPayout(Actor who, UUID period, String amount) throws Exception {
        String json = amount == null
                ? "{\"rotationPeriodId\":\"" + period + "\"}"
                : "{\"rotationPeriodId\":\"" + period + "\",\"amount\":" + amount + "}";
        return mockMvc.perform(post(base() + "/rotation/payouts")
                        .header("Authorization", bearer(who))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andReturn();
    }

    /** The president approves and an accountant confirms the disbursement of a requested payout. */
    public void approveAndDisburse(UUID payout, String reference) throws Exception {
        MvcResult approved = mockMvc.perform(post(base() + "/rotation/payouts/" + payout + "/approve")
                        .header("Authorization", bearer(scheme.president())))
                .andReturn();
        assertThat(status(approved)).as(approved.getResponse().getContentAsString()).isEqualTo(200);
        MvcResult confirmed = mockMvc.perform(post(base() + "/rotation/payouts/" + payout + "/confirm-disbursement")
                        .header("Authorization", bearer(verifier))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"method\":\"BANK_TRANSFER\",\"reference\":\"" + reference + "\",\"note\":\"Transferred\"}"))
                .andReturn();
        assertThat(status(confirmed)).as(confirmed.getResponse().getContentAsString()).isEqualTo(200);
    }

    public Loan loan(String principal, String interest) {
        LocalDate today = LocalDate.now(KIGALI);
        return loanRepository.saveAndFlush(Loan.builder()
                .cooperativeId(scheme.id())
                .memberUserId(payer.id())
                .requestedAmount(new BigDecimal(principal))
                .approvedAmount(new BigDecimal(principal))
                .principalAmount(new BigDecimal(principal))
                .interestRatePercent(new BigDecimal("2"))
                .interestType(InterestType.FLAT)
                .termMonths(6)
                .interestAmount(new BigDecimal(interest))
                .outstandingPrincipal(new BigDecimal(principal))
                .outstandingInterest(new BigDecimal(interest))
                .requestDate(today.minusMonths(1))
                .disbursementDate(today.minusMonths(1))
                .dueDate(today.plusMonths(5))
                .status(LoanStatus.ACTIVE)
                .build());
    }

    public Loan reload(Loan loan) {
        return loanRepository.findById(loan.getId()).orElseThrow();
    }

    public BigDecimal paid(YearMonth month) {
        return jdbc.queryForObject("select paid_amount from contributions where cooperative_id = ? and member_user_id = ? and \"year\" = ? and \"month\" = ?",
                BigDecimal.class, scheme.id(), payer.id(), month.getYear(), month.getMonthValue());
    }

    public BigDecimal outstanding(YearMonth month) {
        return jdbc.queryForObject("select outstanding_amount from contributions where cooperative_id = ? and member_user_id = ? and \"year\" = ? and \"month\" = ?",
                BigDecimal.class, scheme.id(), payer.id(), month.getYear(), month.getMonthValue());
    }

    public BigDecimal availableFunds() throws Exception {
        return data(getAs(poster, "/reports/model-summary")).path("availableFunds").decimalValue();
    }

    /** Credits held less credits applied or reversed, from the journal. */
    public BigDecimal creditJournalBalance() {
        return jdbc.queryForObject("select coalesce(sum(case when entry_type = 'RECEIVED' then amount else -amount end), 0)"
                + " from member_credit_entries where cooperative_id = ?", BigDecimal.class, scheme.id());
    }

    /** The same figure from the ledger. */
    public BigDecimal creditLedgerBalance() {
        return jdbc.queryForObject("select coalesce(sum(credit_amount), 0) - coalesce(sum(debit_amount), 0) from financial_ledger"
                + " where cooperative_id = ? and transaction_type in ('MEMBER_CREDIT_RECEIVED','MEMBER_CREDIT_APPLIED','MEMBER_CREDIT_REVERSED')",
                BigDecimal.class, scheme.id());
    }

    public long count(String sql, Object... args) {
        Long value = jdbc.queryForObject(sql, Long.class, args);
        return value == null ? 0 : value;
    }

    public static int status(MvcResult result) {
        return result.getResponse().getStatus();
    }

    public String code(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString()).path("code").asText();
    }

    public JsonNode data(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString()).path("data");
    }
}
