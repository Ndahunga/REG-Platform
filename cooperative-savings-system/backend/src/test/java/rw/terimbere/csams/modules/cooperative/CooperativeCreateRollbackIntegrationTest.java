package rw.terimbere.csams.modules.cooperative;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
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
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import rw.terimbere.csams.modules.cooperative.repository.CooperativeRepository;
import rw.terimbere.csams.modules.subscription.entity.SubscriptionInitialization;
import rw.terimbere.csams.modules.subscription.service.SubscriptionService;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class CooperativeCreateRollbackIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private CooperativeRepository cooperativeRepository;

    @MockBean
    private SubscriptionService subscriptionService;

    private String superAdminToken;

    @BeforeEach
    void setUp() throws Exception {
        when(subscriptionService.initializeForCooperative(
                        any(UUID.class), any(SubscriptionInitialization.class), any(UUID.class)))
                .thenThrow(new IllegalStateException("subscription initialization failed"));

        MvcResult login = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"superadmin","password":"ChangeMe@123!"}
                                """))
                .andExpect(status().isOk())
                .andReturn();
        superAdminToken = objectMapper
                .readTree(login.getResponse().getContentAsString())
                .path("data")
                .path("accessToken")
                .asText();
    }

    @Test
    void subscriptionInitializationFailure_rollsBackCreate() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        String registrationNumber = "RCA/ROLL/" + suffix;
        String body =
                """
                {
                  "name":"Rollback Coop %s",
                  "currency":"RWF",
                  "monthlyContributionAmount":1000,
                  "contributionDueDay":1,
                  "financialYearStartMonth":1,
                  "registrationNumber":"%s",
                  "contactEmail":"roll-%s@test.local",
                  "contactPhone":"0781234567",
                  "operatingModel":"ACCUMULATING",
                  "registrationDate":"2024-01-15"
                }
                """
                        .formatted(suffix, registrationNumber, suffix.toLowerCase());

        mockMvc.perform(post("/api/v1/cooperatives")
                        .header("Authorization", "Bearer " + superAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().is5xxServerError());

        assertThat(cooperativeRepository.existsByRegistrationNumberIgnoreCaseAndDeletedFalse(registrationNumber))
                .isFalse();
    }
}
