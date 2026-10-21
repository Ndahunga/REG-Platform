package rw.terimbere.csams.modules.onboarding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import rw.terimbere.csams.modules.cooperative.entity.Cooperative;
import rw.terimbere.csams.modules.cooperative.repository.CooperativeRepository;
import rw.terimbere.csams.modules.user.repository.UserRepository;

/**
 * The registration number is optional for PUBLIC self-onboarding only. When omitted nothing is invented:
 * it is stored as null and no duplicate lookup runs. When supplied it is still validated and de-duplicated.
 * The Super Admin create flow keeps requiring it.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PublicOnboardingRegistrationNumberIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UserRepository userRepository;

    @SpyBean
    private CooperativeRepository cooperativeRepository;

    @Test
    void missingRegistrationNumber_isAccepted_andStoredAsNull() throws Exception {
        String suffix = suffix();
        Cooperative created = signupOk(suffix, null);

        assertThat(created.getRegistrationNumber()).isNull();
        assertThat(created.getName()).isEqualTo("Public Scheme " + suffix);
    }

    @Test
    void explicitNullAndBlankRegistrationNumber_areAccepted_andStoredAsNull() throws Exception {
        for (String fragment : new String[] {
            "\"registrationNumber\": null,", "\"registrationNumber\": \"\",", "\"registrationNumber\": \"   \","
        }) {
            Cooperative created = signupOk(suffix(), fragment);
            assertThat(created.getRegistrationNumber())
                    .as("stored value for %s", fragment)
                    .isNull();
        }
    }

    @Test
    void malformedRegistrationNumber_isStillRejected_andNothingIsCreated() throws Exception {
        for (String malformed : new String[] {"A", "bad number!!", "x".repeat(40), "RCA//$$"}) {
            String suffix = suffix();
            mockMvc.perform(post("/api/v1/onboarding/signup")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body(suffix, "\"registrationNumber\": \"" + malformed + "\",")))
                    .andExpect(status().isBadRequest());
            assertThat(userRepository.existsByUsernameIgnoreCaseAndDeletedFalse("onb_" + suffix))
                    .as("no user for malformed %s", malformed)
                    .isFalse();
        }
    }

    @Test
    void validRegistrationNumber_isNormalizedAndStored() throws Exception {
        String suffix = suffix();
        Cooperative created = signupOk(suffix, "\"registrationNumber\": \" rca/opt/" + suffix + " \",");

        assertThat(created.getRegistrationNumber()).isEqualTo("RCA/OPT/" + suffix.toUpperCase());
    }

    @Test
    void duplicateLookup_isSkippedWhenAbsent_andRunWhenProvided() throws Exception {
        clearInvocations(cooperativeRepository);
        signupOk(suffix(), null);
        verify(cooperativeRepository, never()).existsByRegistrationNumberIgnoreCaseAndDeletedFalse(anyString());

        clearInvocations(cooperativeRepository);
        String suffix = suffix();
        signupOk(suffix, "\"registrationNumber\": \"RCA/LOOK/" + suffix.toUpperCase() + "\",");
        verify(cooperativeRepository, times(1))
                .existsByRegistrationNumberIgnoreCaseAndDeletedFalse("RCA/LOOK/" + suffix.toUpperCase());
    }

    @Test
    void severalSchemesWithoutRegistrationNumber_neverConflict() throws Exception {
        Cooperative first = signupOk(suffix(), null);
        Cooperative second = signupOk(suffix(), null);

        assertThat(first.getId()).isNotEqualTo(second.getId());
        assertThat(first.getRegistrationNumber()).isNull();
        assertThat(second.getRegistrationNumber()).isNull();
    }

    @Test
    void aProvidedDuplicateRegistrationNumber_isStillRejected() throws Exception {
        String suffix = suffix();
        String fragment = "\"registrationNumber\": \"RCA/DUPX/" + suffix.toUpperCase() + "\",";
        signupOk(suffix, fragment);

        String other = suffix();
        mockMvc.perform(post("/api/v1/onboarding/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(other, fragment)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("Registration number already in use"));
    }

    @Test
    void superAdminCreate_stillRequiresARegistrationNumber() throws Exception {
        String token = loginSuperAdmin();
        mockMvc.perform(post("/api/v1/cooperatives")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name":"Admin Scheme %s",
                                  "currency":"RWF",
                                  "monthlyContributionAmount":1000,
                                  "contributionDueDay":1,
                                  "financialYearStartMonth":1,
                                  "contactEmail":"admin-%s@test.local",
                                  "contactPhone":"0781234567",
                                  "operatingModel":"ACCUMULATING",
                                  "registrationDate":"2024-01-15"
                                }
                                """.formatted(suffix(), suffix())))
                .andExpect(status().isBadRequest());
    }

    // ------------------------------------------------------------------------------------------ helpers

    private Cooperative signupOk(String suffix, String registrationNumberFragment) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/onboarding/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(suffix, registrationNumberFragment)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andReturn();
        UUID coopId = UUID.fromString(objectMapper
                .readTree(result.getResponse().getContentAsString())
                .path("data")
                .path("user")
                .path("cooperativeIds")
                .get(0)
                .asText());
        return cooperativeRepository.findByIdAndDeletedFalse(coopId).orElseThrow();
    }

    /** {@code registrationNumberFragment} is a raw JSON member (with trailing comma) or null to omit the field. */
    private static String body(String suffix, String registrationNumberFragment) {
        return """
                {
                  "cooperative": {
                    "name":"Public Scheme %s",
                    %s
                    "contactEmail":"%s@test.local",
                    "contactPhone":"0781234567",
                    "address":"Kigali",
                    "currency":"RWF",
                    "financialYearStartMonth":1,
                    "monthlyContributionAmount":5000,
                    "contributionDueDay":5,
                    "operatingModel":"ACCUMULATING",
                    "registrationDate":"2024-01-15"
                  },
                  "creator": {
                    "username":"onb_%s",
                    "email":"onb_%s@test.local",
                    "password":"SignupPass1!",
                    "firstName":"Pat",
                    "lastName":"President"
                  }
                }
                """
                .formatted(suffix, registrationNumberFragment == null ? "" : registrationNumberFragment, suffix, suffix, suffix);
    }

    private String loginSuperAdmin() throws Exception {
        MvcResult login = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"superadmin\",\"password\":\"ChangeMe@123!\"}"))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper
                .readTree(login.getResponse().getContentAsString())
                .path("data")
                .path("accessToken")
                .asText();
    }

    private static String suffix() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}
