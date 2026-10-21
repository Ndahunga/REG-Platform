package rw.terimbere.csams.shared.exceptions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.OptimisticLockException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.support.TransactionTemplate;
import rw.terimbere.csams.modules.cooperative.CooperativeTestFixtures;
import rw.terimbere.csams.modules.fine.entity.Fine;
import rw.terimbere.csams.modules.fine.entity.FineCalculationMode;
import rw.terimbere.csams.modules.fine.entity.FineStatus;
import rw.terimbere.csams.modules.fine.entity.FineType;
import rw.terimbere.csams.modules.fine.repository.FineRepository;
import rw.terimbere.csams.modules.loan.entity.InterestType;
import rw.terimbere.csams.modules.loan.entity.Loan;
import rw.terimbere.csams.modules.loan.entity.LoanStatus;
import rw.terimbere.csams.modules.loan.repository.LoanRepository;

/**
 * Real {@code @Version} conflicts against the test database (H2 in PostgreSQL mode). Two stale copies of the same loan
 * are saved one after the other: the second must fail with a Spring {@link OptimisticLockingFailureException} (never a
 * raw Jakarta one, which is why the handler needs no Jakarta mapping), and its whole transaction must roll back.
 *
 * <p>This is a deterministic stale-write sequence, not a multi-threaded race, and it is not PostgreSQL-verified. The
 * exception-to-HTTP mapping itself is tested in {@code GlobalExceptionHandlerConcurrencyTest}.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class OptimisticLockConflictIntegrationTest {

    private static final ZoneId KIGALI = ZoneId.of("Africa/Kigali");

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private LoanRepository loanRepository;
    @Autowired private FineRepository fineRepository;
    @Autowired private TransactionTemplate transactionTemplate;

    private UUID scheme;
    private UUID borrower;

    @BeforeEach
    void setUp() throws Exception {
        String token = login("superadmin", "ChangeMe@123!");
        MvcResult create = mockMvc.perform(post("/api/v1/cooperatives")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CooperativeTestFixtures.createBody(
                                "OL " + UUID.randomUUID().toString().substring(0, 8), "10000.0000", 1)))
                .andExpect(status().isOk())
                .andReturn();
        scheme = UUID.fromString(
                objectMapper.readTree(create.getResponse().getContentAsString()).path("data").path("id").asText());
        String username = "ol_" + UUID.randomUUID().toString().substring(0, 10);
        MvcResult register = mockMvc.perform(post("/api/v1/cooperatives/" + scheme + "/members")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"firstName":"Ol","lastName":"Member","username":"%s","email":"%s@test.local",
                                 "roleInCooperative":"MEMBER"}
                                """.formatted(username, username)))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode data = objectMapper.readTree(register.getResponse().getContentAsString()).path("data");
        borrower = UUID.fromString(data.path("userId").asText());
    }

    @Test
    void aStaleLoanWriteFailsWithASpringOptimisticLockExceptionAndTheFirstWriteSurvives() {
        Loan saved = loan();
        Loan copyA = loanRepository.findById(saved.getId()).orElseThrow();
        Loan copyB = loanRepository.findById(saved.getId()).orElseThrow();
        assertThat(copyA.getVersion()).isEqualTo(copyB.getVersion());

        copyA.setOutstandingPrincipal(new BigDecimal("50000"));
        loanRepository.saveAndFlush(copyA);

        copyB.setOutstandingPrincipal(new BigDecimal("10000"));
        assertThatThrownBy(() -> loanRepository.saveAndFlush(copyB))
                .isInstanceOf(OptimisticLockingFailureException.class)
                .isNotInstanceOf(OptimisticLockException.class);

        Loan stored = loanRepository.findById(saved.getId()).orElseThrow();
        assertThat(stored.getOutstandingPrincipal()).isEqualByComparingTo("50000");
        assertThat(stored.getVersion()).isEqualTo(copyA.getVersion() + 1);
    }

    @Test
    void aVersionConflictRollsTheWholeTransactionBack() {
        Loan saved = loan();
        Loan stale = loanRepository.findById(saved.getId()).orElseThrow();
        Loan fresh = loanRepository.findById(saved.getId()).orElseThrow();
        fresh.setOutstandingPrincipal(new BigDecimal("50000"));
        loanRepository.saveAndFlush(fresh);
        long finesBefore = fineRepository.count();

        // One transaction writes a fine and then a stale loan: the loan conflict must undo the fine as well.
        assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(tx -> {
                    fineRepository.saveAndFlush(fine());
                    stale.setOutstandingPrincipal(new BigDecimal("10000"));
                    loanRepository.saveAndFlush(stale);
                }))
                .isInstanceOf(OptimisticLockingFailureException.class);

        assertThat(fineRepository.count()).isEqualTo(finesBefore);
        assertThat(loanRepository.findById(saved.getId()).orElseThrow().getOutstandingPrincipal())
                .isEqualByComparingTo("50000");
    }

    private Loan loan() {
        LocalDate today = LocalDate.now(KIGALI);
        return loanRepository.saveAndFlush(Loan.builder()
                .cooperativeId(scheme)
                .memberUserId(borrower)
                .requestedAmount(new BigDecimal("100000"))
                .approvedAmount(new BigDecimal("100000"))
                .principalAmount(new BigDecimal("100000"))
                .interestRatePercent(new BigDecimal("2"))
                .interestType(InterestType.FLAT)
                .termMonths(6)
                .interestAmount(BigDecimal.ZERO)
                .outstandingPrincipal(new BigDecimal("100000"))
                .outstandingInterest(BigDecimal.ZERO)
                .requestDate(today.minusMonths(1))
                .disbursementDate(today.minusMonths(1))
                .dueDate(today.plusMonths(5))
                .status(LoanStatus.ACTIVE)
                .build());
    }

    private Fine fine() {
        return Fine.builder()
                .cooperativeId(scheme)
                .memberUserId(borrower)
                .fineType(FineType.MANUAL)
                .calculationMode(FineCalculationMode.FIXED)
                .baseAmount(new BigDecimal("500"))
                .totalAmount(new BigDecimal("500"))
                .outstandingAmount(new BigDecimal("500"))
                .issuedDate(LocalDate.now(KIGALI))
                .status(FineStatus.UNPAID)
                .build();
    }

    private String login(String username, String password) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"%s\",\"password\":\"%s\"}".formatted(username, password)))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString())
                .path("data")
                .path("accessToken")
                .asText();
    }
}
