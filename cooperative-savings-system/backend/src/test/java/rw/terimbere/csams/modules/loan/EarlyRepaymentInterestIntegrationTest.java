package rw.terimbere.csams.modules.loan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static rw.terimbere.csams.testsupport.PaymentScenario.bearer;
import static rw.terimbere.csams.testsupport.PaymentScenario.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;
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
import rw.terimbere.csams.modules.loan.entity.InterestType;
import rw.terimbere.csams.modules.loan.entity.Loan;
import rw.terimbere.csams.modules.loan.entity.LoanStatus;
import rw.terimbere.csams.modules.loan.repository.LoanRepository;
import rw.terimbere.csams.modules.loan.service.EarlyRepaymentInterest;
import rw.terimbere.csams.testsupport.PaymentScenario;

/**
 * Repaying early: interest only for the months used (a started month counts whole, at least one). Settling takes the
 * principal plus that interest and closes the loan; the interest of the unused months is not owed.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class EarlyRepaymentInterestIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private LoanRepository loanRepository;

    @Test
    void monthsAreCountedWholeFromTheDisbursement() {
        LocalDate start = LocalDate.of(2026, 6, 22);
        assertThat(EarlyRepaymentInterest.monthsCharged(start, LocalDate.of(2026, 6, 22), 5)).isEqualTo(1);
        assertThat(EarlyRepaymentInterest.monthsCharged(start, LocalDate.of(2026, 6, 23), 5)).isEqualTo(1);
        assertThat(EarlyRepaymentInterest.monthsCharged(start, LocalDate.of(2026, 7, 23), 5)).isEqualTo(2);
        assertThat(EarlyRepaymentInterest.monthsCharged(start, LocalDate.of(2026, 9, 21), 5)).isEqualTo(3);
        assertThat(EarlyRepaymentInterest.monthsCharged(start, LocalDate.of(2026, 9, 22), 5)).isEqualTo(3);
        assertThat(EarlyRepaymentInterest.monthsCharged(start, LocalDate.of(2027, 3, 1), 5)).isEqualTo(5);
    }

    @Test
    void repayingAfterTwoMonthsAndADayChargesThreeMonthsAndClosesTheLoan() throws Exception {
        PaymentScenario p = new PaymentScenario(mockMvc, objectMapper, jdbc, loanRepository, "ACCUMULATING", 1);
        LocalDate today = LocalDate.now(PaymentScenario.KIGALI);
        Loan loan = loanRepository.saveAndFlush(Loan.builder()
                .cooperativeId(p.id())
                .memberUserId(p.payer.id())
                .requestedAmount(new BigDecimal("500000"))
                .approvedAmount(new BigDecimal("500000"))
                .principalAmount(new BigDecimal("500000"))
                .interestRatePercent(new BigDecimal("2"))
                .interestType(InterestType.FLAT)
                .termMonths(5)
                .interestAmount(new BigDecimal("50000"))
                .outstandingPrincipal(new BigDecimal("500000"))
                .outstandingInterest(new BigDecimal("50000"))
                .requestDate(today.minusMonths(2).minusDays(1))
                .disbursementDate(today.minusMonths(2).minusDays(1))
                .dueDate(today.plusMonths(3))
                .status(LoanStatus.ACTIVE)
                .build());
        String path = p.base() + "/loans/" + loan.getId();

        JsonNode quote = p.data(mockMvc.perform(get(path + "/settlement").header("Authorization", bearer(p.reviewer))).andReturn());
        assertThat(quote.path("monthsCharged").asInt()).isEqualTo(3);
        assertThat(quote.path("interestToPay").decimalValue()).isEqualByComparingTo("30000");
        assertThat(quote.path("total").decimalValue()).isEqualByComparingTo("530000");
        assertThat(quote.path("interestWaived").decimalValue()).isEqualByComparingTo("20000");

        MvcResult tooMuch = repay(p, path, "550000", today);
        assertThat(status(tooMuch)).isEqualTo(422);
        assertThat(p.code(tooMuch)).isEqualTo("REPAYMENT_EXCEEDS_SETTLEMENT");

        // a part payment takes only the interest earned so far, the rest goes to the principal
        MvcResult part = repay(p, path, "100000", today);
        assertThat(status(part)).as(part.getResponse().getContentAsString()).isEqualTo(200);
        assertThat(p.data(part).path("interestPortion").decimalValue()).isEqualByComparingTo("30000");
        assertThat(p.data(part).path("principalPortion").decimalValue()).isEqualByComparingTo("70000");

        MvcResult settled = repay(p, path, "430000", today);
        assertThat(status(settled)).as(settled.getResponse().getContentAsString()).isEqualTo(200);
        assertThat(p.data(settled).path("principalPortion").decimalValue()).isEqualByComparingTo("430000");
        assertThat(p.data(settled).path("interestPortion").decimalValue()).isEqualByComparingTo("0");
        assertThat(p.data(settled).path("interestWaived").decimalValue()).isEqualByComparingTo("20000");
        Loan closed = p.reload(loan);
        assertThat(closed.getStatus()).isEqualTo(LoanStatus.CLOSED);
        assertThat(closed.getOutstandingInterest()).isEqualByComparingTo("0");
        assertThat(closed.getInterestAmount()).isEqualByComparingTo("30000");

        // revising the settling repayment gives the waived interest back to the loan first
        UUID settledId = UUID.fromString(p.data(settled).path("id").asText());
        MvcResult revised = mockMvc.perform(post(path + "/repayments/" + settledId + "/revise")
                        .header("Authorization", bearer(p.verifier))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("amount", "400000", "reason", "Only 400,000 was received"))))
                .andReturn();
        assertThat(status(revised)).as(revised.getResponse().getContentAsString()).isEqualTo(200);
        Loan reopened = p.reload(loan);
        assertThat(reopened.getStatus()).isEqualTo(LoanStatus.ACTIVE);
        assertThat(reopened.getOutstandingPrincipal()).isEqualByComparingTo("30000");
        assertThat(reopened.getOutstandingInterest()).isEqualByComparingTo("20000");
        assertThat(reopened.getInterestAmount()).isEqualByComparingTo("50000");
    }

    private MvcResult repay(PaymentScenario p, String path, String amount, LocalDate date) throws Exception {
        return mockMvc.perform(post(path + "/repayments")
                        .header("Authorization", bearer(p.reviewer))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("amount", amount, "paymentDate", date.toString()))))
                .andReturn();
    }
}
