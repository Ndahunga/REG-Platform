package rw.terimbere.csams.concurrency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import rw.terimbere.csams.modules.memberpayment.verification.ProviderCallbackProperties;
import rw.terimbere.csams.testsupport.SchemeFixtures;
import rw.terimbere.csams.testsupport.SchemeFixtures.Actor;
import rw.terimbere.csams.testsupport.SchemeFixtures.Scheme;

/**
 * Racing the financial operations that must happen exactly once: posting a payment, confirming a payout, executing a
 * reversal, approving and uploading a historical import, and replaying a provider callback. Each scenario fires the
 * requests together from separate threads through the real HTTP stack and then reads the books: whatever the interleaving,
 * the money moved once.
 *
 * <p>Database agnostic: {@code FinancialConcurrencyH2Test} runs it in the normal suite, {@code
 * FinancialConcurrencyPostgresIT} on a real PostgreSQL.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
abstract class FinancialConcurrencyScenarios {

    private static final ZoneId KIGALI = ZoneId.of("Africa/Kigali");
    private static final YearMonth NOW = YearMonth.now(KIGALI);
    private static final YearMonth NEXT = NOW.plusMonths(1);
    private static final String SECRET = "concurrency-test-secret";

    @FunctionalInterface
    private interface Task<T> {
        int run(T input) throws Exception;
    }

    @Autowired protected MockMvc mockMvc;
    @Autowired protected ObjectMapper objectMapper;
    @Autowired protected JdbcTemplate jdbc;
    @Autowired private ProviderCallbackProperties callbackProperties;

    private SchemeFixtures fixtures;

    @BeforeEach
    void setUp() throws Exception {
        fixtures = new SchemeFixtures(mockMvc, objectMapper, jdbc);
    }

    @AfterEach
    void tearDown() {
        callbackProperties.setSecrets(Map.of());
    }

    // ============================================================ posting

    @Test
    void twoOfficersPostingTheSamePaymentTogetherCreditTheFundOnce() throws Exception {
        Scheme s = fixtures.scheme("ACCUMULATING");
        Actor verifier = fixtures.register(s.id(), "ACCOUNTANT");
        jdbc.update("""
                insert into contributions (id, cooperative_id, member_user_id, "year", "month", expected_amount, paid_amount,
                  outstanding_amount, status, ledger_revision, created_at, updated_at, version)
                values (?, ?, ?, ?, ?, 5000, 0, 5000, 'PENDING', 0, now(), now(), 0)
                """, UUID.randomUUID(), s.id(), s.memberA().id(), NOW.getYear(), NOW.getMonthValue());
        UUID payment = verifiedPayment(s, verifier, "5000", NOW);

        List<Integer> statuses = concurrently(List.of(s.accountantA(), s.accountantB()), poster -> status(act(s, poster, payment, "/post", null)));

        assertThat(statuses).as("a repeated post is a safe no-op, never a second posting").containsOnly(200);
        assertThat(count("select count(*) from member_payment_postings where payment_id = ?", payment)).isEqualTo(1);
        assertThat(count("select count(*) from financial_ledger where cooperative_id = ? and transaction_type = 'REGULAR_CONTRIBUTION' and status = 'APPROVED'", s.id()))
                .isEqualTo(1);
        assertThat(dec("select paid_amount from contributions where cooperative_id = ?", s.id())).isEqualByComparingTo("5000");
        assertThat(jdbc.queryForObject("select status from member_payments where id = ?", String.class, payment)).isEqualTo("POSTED");
    }

    // ============================================================ payout and reversal

    @Test
    void twoConfirmationsOfTheSamePayoutDisburseOnce() throws Exception {
        Scheme s = fixtures.scheme("HYBRID");
        Actor verifier = fixtures.register(s.id(), "ACCOUNTANT");
        Actor recipient2 = fixtures.register(s.id(), "MEMBER");
        rotation(s, recipient2);
        UUID payment = verifiedPayment(s, verifier, "2000", NEXT);
        assertThat(status(act(s, s.accountantA(), payment, "/post", null))).isEqualTo(200);
        UUID period = periodOf(s, NEXT);
        UUID payout = UUID.fromString(data(mockMvc.perform(post(base(s) + "/rotation/payouts")
                        .header("Authorization", bearer(s.accountantA()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rotationPeriodId\":\"" + period + "\"}")).andReturn()).path("id").asText());
        assertThat(status(mockMvc.perform(post(base(s) + "/rotation/payouts/" + payout + "/approve")
                .header("Authorization", bearer(s.president()))).andReturn())).isEqualTo(200);

        List<Integer> statuses = concurrently(List.of(s.accountantB(), verifier), who -> status(mockMvc.perform(
                        post(base(s) + "/rotation/payouts/" + payout + "/confirm-disbursement")
                                .header("Authorization", bearer(who))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"method\":\"BANK_TRANSFER\",\"reference\":\"BK-" + who.username() + "\",\"note\":\"Transferred\"}"))
                .andReturn()));

        assertThat(statuses.stream().filter(code -> code == 200).count()).as("two different confirmations: exactly one wins: " + statuses).isEqualTo(1);
        assertThat(statuses.stream().filter(code -> code == 409).count()).as("the other is refused as already disbursed").isEqualTo(1);
        assertThat(jdbc.queryForObject("select status from rotation_payouts where id = ?", String.class, payout)).isEqualTo("DISBURSED");
        assertThat(count("select count(*) from financial_ledger where cooperative_id = ? and source_entity_type = 'ROTATION_PAYOUT'", s.id())).isEqualTo(1);
        assertThat(dec("select coalesce(sum(debit_amount), 0) from financial_ledger where cooperative_id = ? and source_entity_type = 'ROTATION_PAYOUT'", s.id()))
                .isEqualByComparingTo("1000");
    }

    @Test
    void twoApprovalsOfTheSameReversalTakeTheMoneyBackOnce() throws Exception {
        Scheme s = fixtures.scheme("HYBRID");
        Actor verifier = fixtures.register(s.id(), "ACCOUNTANT");
        Actor recipient2 = fixtures.register(s.id(), "MEMBER");
        rotation(s, recipient2);
        UUID payment = verifiedPayment(s, verifier, "1000", NEXT);
        assertThat(status(act(s, s.accountantA(), payment, "/post", null))).isEqualTo(200);
        UUID reversal = UUID.fromString(data(act(s, s.accountantA(), payment, "/reversals", "{\"reason\":\"Wrong period\"}")).path("id").asText());

        // the President and Super Admin both hold the fund authority; neither requested nor paid
        List<Actor> deciders = List.of(s.president(), new Actor(fixtures.superAdminId(), "superadmin", fixtures.superAdminToken()));
        List<Integer> statuses = concurrently(deciders, who -> status(mockMvc.perform(
                        post(base(s) + "/payments/reversals/" + reversal + "/approve").header("Authorization", bearer(who))).andReturn()));

        assertThat(statuses).as("the second approval of an executed reversal is a no-op").containsOnly(200);
        assertThat(dec("select paid_amount from contributions where cooperative_id = ? and member_user_id = ?", s.id(), s.memberA().id()))
                .as("taken back exactly once").isEqualByComparingTo("0");
        assertThat(jdbc.queryForObject("select status from member_payments where id = ?", String.class, payment)).isEqualTo("REVERSED");
        assertThat(count("select count(*) from financial_ledger where cooperative_id = ? and transaction_type = 'REVERSAL'", s.id())).isEqualTo(1);
    }

    // ============================================================ import

    @Test
    void concurrentUploadsOfOneFileAreOneBatchAndConcurrentApprovalsPostItOnce() throws Exception {
        Scheme s = fixtures.scheme("ACCUMULATING");
        byte[] file = workbook(s);
        List<String> ids = new ArrayList<>();
        List<Integer> uploads = concurrently(List.of(s.accountantA(), s.accountantB()), who -> {
            MvcResult result = mockMvc.perform(multipart(importBase(s) + "/preview")
                            .file(new MockMultipartFile("file", "history.xlsx", MediaType.APPLICATION_OCTET_STREAM_VALUE, file))
                            .header("Authorization", bearer(who)))
                    .andReturn();
            synchronized (ids) {
                ids.add(data(result).path("id").asText());
            }
            return status(result);
        });
        assertThat(uploads).containsOnly(200);
        assertThat(ids).as("the same file is one batch").hasSize(2).containsOnly(ids.get(0));
        assertThat(count("select count(*) from historical_ledger_imports where cooperative_id = ?", s.id())).isEqualTo(1);

        String id = ids.get(0);
        List<Actor> approvers = List.of(s.president(), new Actor(fixtures.superAdminId(), "superadmin", fixtures.superAdminToken()));
        List<Integer> approvals = concurrently(approvers, who -> status(mockMvc.perform(
                        post(importBase(s) + "/" + id + "/approve").header("Authorization", bearer(who))).andReturn()));

        assertThat(approvals).as("both succeed; the second sees an already posted batch").containsOnly(200);
        assertThat(count("select count(*) from financial_ledger where cooperative_id = ?", s.id())).as("each row posted once").isEqualTo(2);
        assertThat(count("select count(*) from contributions where cooperative_id = ?", s.id())).isEqualTo(1);
        assertThat(count("select count(*) from income_expense_transactions where cooperative_id = ?", s.id())).isEqualTo(1);
        assertThat(count("select count(*) from historical_ledger_rows where cooperative_id = ? and posted_key is not null", s.id())).isEqualTo(2);
    }

    // ============================================================ callbacks

    @Test
    void aReplayedProviderCallbackIsRecordedOnceWhateverTheInterleaving() throws Exception {
        Scheme s = fixtures.scheme("ACCUMULATING");
        callbackProperties.setSecrets(Map.of("MTN_MOMO", SECRET));
        String body = "{\"cooperativeId\":\"%s\",\"eventId\":\"evt-race\",\"reference\":\"RACE-REF-1\",\"amount\":1500,\"currency\":\"RWF\",\"status\":\"SUCCESS\"}"
                .formatted(s.id());
        String timestamp = String.valueOf(java.time.Instant.now().getEpochSecond());
        String signature = sign(timestamp, body);
        List<String> codes = new ArrayList<>();
        List<Integer> statuses = concurrently(List.of(1, 2, 3, 4, 5), n -> {
            MvcResult result = mockMvc.perform(post("/api/v1/public/payment-callbacks/MTN_MOMO")
                            .contentType(MediaType.APPLICATION_JSON)
                            .header("X-Timestamp", timestamp)
                            .header("X-Signature", signature)
                            .content(body.getBytes(StandardCharsets.UTF_8)))
                    .andReturn();
            synchronized (codes) {
                codes.add(objectMapper.readTree(result.getResponse().getContentAsString()).path("code").asText());
            }
            return status(result);
        });
        assertThat(statuses).containsOnly(200);
        assertThat(codes.stream().filter("CREATED"::equals).count()).as("one delivery creates it: " + codes).isEqualTo(1);
        assertThat(codes.stream().filter("DUPLICATE"::equals).count()).isEqualTo(4);
        assertThat(count("select count(*) from trusted_transactions where cooperative_id = ?", s.id())).isEqualTo(1);
    }

    // ============================================================ helpers

    private UUID verifiedPayment(Scheme s, Actor verifier, String amount, YearMonth month) throws Exception {
        String body = "{\"amount\":%s,\"paymentDate\":\"%s\",\"paymentMethod\":\"MTN_MOMO\",\"transactionReference\":\"CC%s\",\"allocations\":[{\"type\":\"CONTRIBUTION\",\"amount\":%s,\"periodYear\":%d,\"periodMonth\":%d}]}"
                .formatted(amount, LocalDate.now(KIGALI), UUID.randomUUID().toString().replace("-", "").substring(0, 10).toUpperCase(),
                        amount, month.getYear(), month.getMonthValue());
        MvcResult submitted = mockMvc.perform(post(base(s) + "/payments")
                        .header("Authorization", bearer(s.memberA()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andReturn();
        assertThat(status(submitted)).as(submitted.getResponse().getContentAsString()).isEqualTo(200);
        UUID id = UUID.fromString(data(submitted).path("id").asText());
        assertThat(status(act(s, s.accountantB(), id, "/start-review", null))).isEqualTo(200);
        MvcResult verified = act(s, verifier, id, "/verify", "{\"method\":\"PROVIDER_CONFIRMED\",\"basis\":\"Provider confirmation seen\",\"reference\":\"PROV-1\"}");
        assertThat(status(verified)).as(verified.getResponse().getContentAsString()).isEqualTo(200);
        return id;
    }

    private void rotation(Scheme s, Actor recipient2) throws Exception {
        jdbc.update("update cooperative_memberships set share_count = 2 where cooperative_id = ? and user_id = ?", s.id(), s.memberA().id());
        String admin = "Bearer " + fixtures.superAdminToken();
        assertThat(status(mockMvc.perform(post(base(s) + "/rotation/configurations").header("Authorization", admin)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("effectiveYear", NEXT.getYear(), "effectiveMonth", NEXT.getMonthValue(),
                        "rotatingBps", 5000, "retainedBps", 5000)))).andReturn())).isEqualTo(200);
        MvcResult cycle = mockMvc.perform(post(base(s) + "/rotation/cycles").header("Authorization", admin)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("startYear", NEXT.getYear(), "startMonth", NEXT.getMonthValue(),
                        "recipientUserIds", List.of(s.memberB().id(), recipient2.id()))))).andReturn();
        assertThat(status(cycle)).isEqualTo(200);
        assertThat(status(mockMvc.perform(post(base(s) + "/rotation/cycles/" + data(cycle).path("id").asText() + "/activate")
                .header("Authorization", admin)).andReturn())).isEqualTo(200);
        Map<String, Object> line = new LinkedHashMap<>();
        line.put("memberUserId", s.memberA().id());
        line.put("paidAmount", BigDecimal.ZERO);
        assertThat(status(mockMvc.perform(put(base(s) + "/contributions/period?year=" + NEXT.getYear() + "&month=" + NEXT.getMonthValue())
                .header("Authorization", admin)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("lines", List.of(line))))).andReturn())).isEqualTo(200);
    }

    private byte[] workbook(Scheme s) throws Exception {
        try (Workbook workbook = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            fill(workbook.createSheet("Members and Configuration"),
                    List.of("Row Type", "Username", "Full Name", "Share Count", "Setting", "Value"),
                    List.of(new String[] {"SETTING", "", "", "", "OPENING_BALANCE", "0"},
                            new String[] {"SETTING", "", "", "", "DECLARED_CLOSING_BALANCE", "5000"}));
            fill(workbook.createSheet("Historical Transactions"),
                    List.of("Row Type", "Date", "Member Username", "Amount", "Original Reference", "Period Year", "Period Month",
                            "Loan Reference", "Operating Model At The Time", "Description"),
                    List.of(new String[] {"CONTRIBUTION", "2022-01-10", s.memberA().username(), "4000", "C1", "2022", "1", "", "", ""},
                            new String[] {"OTHER_INCOME", "2022-02-01", "", "1000", "OI", "", "", "", "", ""}));
            workbook.write(out);
            return out.toByteArray();
        }
    }

    private static void fill(Sheet sheet, List<String> headers, List<String[]> rows) {
        Row header = sheet.createRow(0);
        for (int c = 0; c < headers.size(); c++) {
            header.createCell(c).setCellValue(headers.get(c));
        }
        int r = 1;
        for (String[] cells : rows) {
            Row row = sheet.createRow(r++);
            for (int c = 0; c < cells.length; c++) {
                row.createCell(c).setCellValue(cells[c]);
            }
        }
    }

    private static String sign(String timestamp, String body) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        mac.update((timestamp + ".").getBytes(StandardCharsets.UTF_8));
        return HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
    }

    private <T> List<Integer> concurrently(List<T> inputs, Task<T> task) throws Exception {
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(inputs.size());
        try {
            List<Future<Integer>> futures = new ArrayList<>();
            for (T input : inputs) {
                Callable<Integer> call = () -> {
                    go.await();
                    return task.run(input);
                };
                futures.add(pool.submit(call));
            }
            go.countDown();
            List<Integer> results = new ArrayList<>();
            for (Future<Integer> future : futures) {
                results.add(future.get(120, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }

    private MvcResult act(Scheme s, Actor who, UUID payment, String suffix, String json) throws Exception {
        var request = post(base(s) + "/payments/" + payment + suffix).header("Authorization", bearer(who));
        if (json != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(json);
        }
        return mockMvc.perform(request).andReturn();
    }

    private UUID periodOf(Scheme s, YearMonth month) {
        return jdbc.queryForObject("select id from rotation_periods where cooperative_id = ? and period_year = ? and period_month = ?",
                UUID.class, s.id(), month.getYear(), month.getMonthValue());
    }

    private static String base(Scheme s) {
        return "/api/v1/cooperatives/" + s.id();
    }

    private static String importBase(Scheme s) {
        return base(s) + "/historical-transactions";
    }

    private long count(String sql, Object... args) {
        Long value = jdbc.queryForObject(sql, Long.class, args);
        return value == null ? 0 : value;
    }

    private BigDecimal dec(String sql, Object... args) {
        return jdbc.queryForObject(sql, BigDecimal.class, args);
    }

    private static String bearer(Actor who) {
        return "Bearer " + who.token();
    }

    private static int status(MvcResult result) {
        return result.getResponse().getStatus();
    }

    private JsonNode data(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString()).path("data");
    }
}
