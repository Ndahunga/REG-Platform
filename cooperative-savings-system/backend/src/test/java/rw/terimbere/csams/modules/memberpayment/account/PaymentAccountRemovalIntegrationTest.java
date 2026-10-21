package rw.terimbere.csams.modules.memberpayment.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static rw.terimbere.csams.testsupport.PaymentScenario.NOW;
import static rw.terimbere.csams.testsupport.PaymentScenario.contribution;
import static rw.terimbere.csams.testsupport.PaymentScenario.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import rw.terimbere.csams.modules.loan.repository.LoanRepository;
import rw.terimbere.csams.testsupport.PaymentScenario;
import rw.terimbere.csams.testsupport.SchemeFixtures.Actor;

/**
 * A payment account that was never used can be deleted. One that payments or receipts were handled against is only ever
 * deactivated, so the history of those payments stays explainable.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PaymentAccountRemovalIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private LoanRepository loanRepository;

    private PaymentScenario scheme() throws Exception {
        return new PaymentScenario(mockMvc, objectMapper, jdbc, loanRepository, "ACCUMULATING", 30);
    }

    private UUID add(PaymentScenario p, Actor who, String channel, String identifier) throws Exception {
        MvcResult result = mockMvc.perform(post(p.base() + "/payments/accounts")
                        .header("Authorization", PaymentScenario.bearer(who))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"channel\":\"" + channel + "\",\"accountIdentifier\":\"" + identifier + "\",\"label\":\"Ikimina\"}"))
                .andReturn();
        assertThat(status(result)).as(result.getResponse().getContentAsString()).isEqualTo(200);
        return UUID.fromString(p.data(result).path("id").asText());
    }

    private JsonNode accountOf(PaymentScenario p, Actor who, UUID id) throws Exception {
        for (JsonNode a : p.data(p.getAs(who, "/payments/accounts"))) {
            if (id.toString().equals(a.path("id").asText())) {
                return a;
            }
        }
        return null;
    }

    private MvcResult removeAccount(PaymentScenario p, Actor who, UUID id) throws Exception {
        return mockMvc.perform(delete(p.base() + "/payments/accounts/" + id).header("Authorization", PaymentScenario.bearer(who))).andReturn();
    }

    private MvcResult deactivate(PaymentScenario p, Actor who, UUID id) throws Exception {
        return mockMvc.perform(post(p.base() + "/payments/accounts/" + id + "/deactivate").header("Authorization", PaymentScenario.bearer(who))).andReturn();
    }

    @Test
    void anAccountNobodyHasPaidIntoCanBeDeletedAndTheDeletionIsAudited() throws Exception {
        PaymentScenario p = scheme();
        UUID id = add(p, p.scheme.president(), "MTN_MOMO", "0788111222");
        JsonNode before = accountOf(p, p.scheme.president(), id);
        assertThat(before.path("canDelete").asBoolean()).isTrue();
        assertThat(before.path("deletionBlockedReason").isNull()).isTrue();

        assertThat(status(removeAccount(p, p.scheme.president(), id))).isEqualTo(200);
        assertThat(accountOf(p, p.scheme.president(), id)).isNull();
        assertThat(p.count("select count(*) from scheme_payment_accounts where id = ?", id)).isZero();
        assertThat(p.count("select count(*) from audit_logs where cooperative_id = ? and action = 'PAYMENT_ACCOUNT_DELETED' and entity_id = ?", p.id(), id)).isEqualTo(1);
    }

    @Test
    void anAccountThatPaymentsWereHandledAgainstCannotBeDeletedOnlyDeactivatedAndTheHistoryStays() throws Exception {
        PaymentScenario p = scheme();
        UUID id = add(p, p.scheme.president(), "MTN_MOMO", "0788333444");
        p.row(NOW);
        UUID payment = p.posted("30000", contribution(NOW, "30000"));

        JsonNode used = accountOf(p, p.scheme.president(), id);
        assertThat(used.path("canDelete").asBoolean()).isFalse();
        assertThat(used.path("deletionBlockedReason").asText()).isEqualTo("PAYMENT_ACCOUNT_IN_USE");
        MvcResult refused = removeAccount(p, p.scheme.president(), id);
        assertThat(status(refused)).isEqualTo(409);
        assertThat(p.code(refused)).isEqualTo("PAYMENT_ACCOUNT_IN_USE");
        assertThat(p.count("select count(*) from scheme_payment_accounts where id = ?", id)).isEqualTo(1);

        assertThat(status(deactivate(p, p.scheme.president(), id))).isEqualTo(200);
        // officers still see it (inactive, with its history); members no longer see it, so it is not offered for new payments
        assertThat(accountOf(p, p.scheme.president(), id).path("active").asBoolean()).isFalse();
        assertThat(accountOf(p, p.payer, id)).isNull();
        assertThat(jdbc.queryForObject("select status from member_payments where id = ?", String.class, payment)).isEqualTo("POSTED");
        assertThat(p.count("select count(*) from audit_logs where cooperative_id = ? and action = 'PAYMENT_ACCOUNT_DEACTIVATED' and entity_id = ?", p.id(), id)).isEqualTo(1);
    }

    @Test
    void onlyThoseWhoManageTheSchemeMayRemoveAccountsAndNeverAnotherSchemesAccount() throws Exception {
        PaymentScenario a = scheme();
        PaymentScenario b = scheme();
        UUID accountOfA = add(a, a.scheme.president(), "MTN_MOMO", "0788555666");
        UUID accountOfB = add(b, b.scheme.president(), "MTN_MOMO", "0788777888");

        assertThat(status(removeAccount(a, a.payer, accountOfA))).as("a member").isEqualTo(403);
        assertThat(status(deactivate(a, a.payer, accountOfA))).isEqualTo(403);
        // A's president aiming at B's account through A's scheme: not found, nothing revealed
        assertThat(status(removeAccount(a, a.scheme.president(), accountOfB))).isEqualTo(404);
        assertThat(status(deactivate(a, a.scheme.president(), accountOfB))).isEqualTo(404);
        // through B's scheme A's president is not a member
        assertThat(status(removeAccount(b, a.scheme.president(), accountOfB))).isEqualTo(403);
        assertThat(a.count("select count(*) from scheme_payment_accounts where id in (?, ?)", accountOfA, accountOfB)).isEqualTo(2);
        assertThat(status(removeAccount(a, a.scheme.president(), UUID.randomUUID()))).isEqualTo(404);
    }
}
