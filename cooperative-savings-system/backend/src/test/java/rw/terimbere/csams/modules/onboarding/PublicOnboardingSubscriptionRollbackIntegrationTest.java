package rw.terimbere.csams.modules.onboarding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
import rw.terimbere.csams.modules.cooperative.repository.CooperativeRepository;
import rw.terimbere.csams.modules.membership.repository.CooperativeMembershipRepository;
import rw.terimbere.csams.modules.subscription.entity.SubscriptionInitialization;
import rw.terimbere.csams.modules.subscription.service.SubscriptionService;
import rw.terimbere.csams.modules.user.repository.UserRepository;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PublicOnboardingSubscriptionRollbackIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private CooperativeRepository cooperativeRepository;

    @Autowired
    private CooperativeMembershipRepository membershipRepository;

    @MockBean
    private SubscriptionService subscriptionService;

    @BeforeEach
    void setUp() {
        when(subscriptionService.initializeForCooperative(
                        any(UUID.class), any(SubscriptionInitialization.class), any(UUID.class)))
                .thenThrow(new IllegalStateException("subscription initialization failed"));
    }

    @Test
    void subscriptionFailure_rollsBackUserCooperativeAndMembership() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String username = "rollsub_" + suffix;
        String registrationNumber = "RCA/RSUB/" + suffix.toUpperCase();
        long membershipsBefore = membershipRepository.count();

        mockMvc.perform(post("/api/v1/onboarding/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(username, registrationNumber)))
                .andExpect(status().is5xxServerError());

        assertThat(userRepository.findByUsernameIgnoreCaseAndDeletedFalse(username)).isEmpty();
        assertThat(cooperativeRepository.existsByRegistrationNumberIgnoreCaseAndDeletedFalse(registrationNumber))
                .isFalse();
        assertThat(membershipRepository.count()).isEqualTo(membershipsBefore);
    }

    private static String body(String username, String registrationNumber) {
        return """
                {
                  "cooperative": {
                    "name":"Rollback Sub %s",
                    "registrationNumber":"%s",
                    "contactEmail":"%s@test.local",
                    "contactPhone":"0781234567",
                    "financialYearStartMonth":1,
                    "monthlyContributionAmount":1000,
                    "contributionDueDay":1,
                    "operatingModel":"ACCUMULATING",
                    "registrationDate":"2024-01-15"
                  },
                  "creator": {
                    "username":"%s",
                    "email":"%s@test.local",
                    "password":"SignupPass1!",
                    "firstName":"Roll",
                    "lastName":"Back"
                  }
                }
                """
                .formatted(username, registrationNumber, username, username, username);
    }
}
