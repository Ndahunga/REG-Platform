package rw.terimbere.csams.modules.historicalimport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyIterable;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.domain.Pageable;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import rw.terimbere.csams.modules.cooperative.CooperativeTestFixtures;
import rw.terimbere.csams.modules.historicalimport.entity.HistoricalImportStatus;
import rw.terimbere.csams.modules.historicalimport.repository.HistoricalImportRepository;
import rw.terimbere.csams.modules.loan.repository.LoanInstallmentRepository;
import rw.terimbere.csams.modules.loan.repository.LoanRepository;
import rw.terimbere.csams.modules.membership.repository.CooperativeMembershipRepository;
import rw.terimbere.csams.modules.membership.repository.MemberOpeningBalanceRepository;
import rw.terimbere.csams.modules.user.repository.UserRepository;

/** A failure late in confirm (installment generation) must leave no members, balances or loans behind. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class OpeningBalanceMigrationRollbackIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private UserRepository userRepository;
    @Autowired private CooperativeMembershipRepository membershipRepository;
    @Autowired private MemberOpeningBalanceRepository openingBalanceRepository;
    @Autowired private LoanRepository loanRepository;
    @Autowired private HistoricalImportRepository historicalImportRepository;

    @MockBean private LoanInstallmentRepository loanInstallmentRepository;

    private String token;
    private UUID cooperativeId;

    @BeforeEach
    void setUp() throws Exception {
        when(loanInstallmentRepository.saveAll(anyIterable())).thenThrow(new RuntimeException("installment write failed"));
        MvcResult login = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"superadmin","password":"ChangeMe@123!"}
                                """))
                .andReturn();
        token = objectMapper.readTree(login.getResponse().getContentAsString()).path("data").path("accessToken").asText();
        MvcResult create = mockMvc.perform(post("/api/v1/cooperatives")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CooperativeTestFixtures.createBody("Rollback Mig " + UUID.randomUUID().toString().substring(0, 8))))
                .andReturn();
        cooperativeId = UUID.fromString(
                objectMapper.readTree(create.getResponse().getContentAsString()).path("data").path("id").asText());
    }

    @Test
    void aFailingLoanImportRollsBackItsLoansAndLeavesTheConfirmedOpeningBalancesIntact() throws Exception {
        String username = "rb_" + UUID.randomUUID().toString().substring(0, 8);
        String root = "/api/v1/cooperatives/" + cooperativeId + "/historical-imports";
        // the opening balances (members and share capital) import fine: they write no instalments
        UUID openingId = preview(MigrationWorkbooks.create()
                .member("Roll Back", username, "50000")
                .shares(username, "3", "30000")
                .file("rb-open.xlsx"), "800000");
        mockMvc.perform(post(root + "/" + openingId + "/confirm").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());

        // the loan import fails late (instalment generation) and must leave no loan behind
        UUID loanImportId = preview(MigrationWorkbooks.create()
                .loan(username, "END_OF_TERM", "100000", "2", "2026-08-01", "6", "", "", "")
                .file("rb-loans.xlsx"), null);
        mockMvc.perform(post(root + "/" + loanImportId + "/confirm").header("Authorization", "Bearer " + token))
                .andExpect(status().is5xxServerError());

        assertThat(loanRepository.findByCooperativeId(cooperativeId, Pageable.unpaged()).getContent()).isEmpty();
        assertThat(historicalImportRepository.findById(loanImportId).orElseThrow().getStatus())
                .isEqualTo(HistoricalImportStatus.FAILED);
        assertThat(historicalImportRepository.findById(openingId).orElseThrow().getStatus())
                .isEqualTo(HistoricalImportStatus.CONFIRMED);
        assertThat(userRepository.findByUsernameIgnoreCaseAndDeletedFalse(username)).isPresent();
        assertThat(openingBalanceRepository.findByCooperativeId(cooperativeId)).hasSize(1);
        assertThat(membershipRepository.findAll().stream().filter(m -> cooperativeId.equals(m.getCooperativeId())))
                .anyMatch(m -> "MEMBER".equals(m.getRoleInCooperative()) && m.getShareCount() == 3);
    }

    private UUID preview(org.springframework.mock.web.MockMultipartFile file, String funds) throws Exception {
        var request = multipart("/api/v1/cooperatives/" + cooperativeId + "/historical-imports/preview")
                .file(file)
                .param("cutoverDate", "2026-09-30")
                .header("Authorization", "Bearer " + token);
        if (funds != null) {
            request.param("openingAvailableFunds", funds);
        }
        MvcResult preview = mockMvc.perform(request).andExpect(status().isOk()).andReturn();
        return UUID.fromString(
                objectMapper.readTree(preview.getResponse().getContentAsString()).path("data").path("importId").asText());
    }
}
