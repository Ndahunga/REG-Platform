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
import rw.terimbere.csams.modules.membership.entity.CooperativeMembership;
import rw.terimbere.csams.modules.membership.repository.CooperativeMembershipRepository;
import rw.terimbere.csams.modules.user.repository.UserRepository;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PublicOnboardingMembershipRollbackIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private CooperativeRepository cooperativeRepository;

    @MockBean
    private CooperativeMembershipRepository membershipRepository;

    @BeforeEach
    void setUp() {
        when(membershipRepository.save(any(CooperativeMembership.class)))
                .thenThrow(new IllegalStateException("membership creation failed"));
    }

    @Test
    void membershipFailure_rollsBackUserAndCooperative() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String username = "rollmem_" + suffix;
        String registrationNumber = "RCA/RMEM/" + suffix.toUpperCase();
        long usersBefore = userRepository.count();
        long cooperativesBefore = cooperativeRepository.count();

        mockMvc.perform(post("/api/v1/onboarding/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(username, registrationNumber)))
                .andExpect(status().is5xxServerError());

        assertThat(userRepository.findByUsernameIgnoreCaseAndDeletedFalse(username)).isEmpty();
        assertThat(cooperativeRepository.existsByRegistrationNumberIgnoreCaseAndDeletedFalse(registrationNumber))
                .isFalse();
        assertThat(userRepository.count()).isEqualTo(usersBefore);
        assertThat(cooperativeRepository.count()).isEqualTo(cooperativesBefore);
    }

    private static String body(String username, String registrationNumber) {
        return """
                {
                  "cooperative": {
                    "name":"Rollback Mem %s",
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
