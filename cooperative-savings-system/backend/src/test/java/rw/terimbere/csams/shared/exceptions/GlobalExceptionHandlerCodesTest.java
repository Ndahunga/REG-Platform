package rw.terimbere.csams.shared.exceptions;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * Regression tests for the shared error contract: HTTP statuses and messages are unchanged, and business and
 * conflict errors now additionally carry a stable machine-readable {@code code}.
 */
class GlobalExceptionHandlerCodesTest {

    private MockMvc mockMvc;

    @RestController
    static class ThrowingController {
        @GetMapping("/test/{kind}")
        String boom(@PathVariable String kind) {
            switch (kind) {
                case "business" -> throw new BusinessException("SOME_RULE", "A rule was broken");
                case "business-default" -> throw new BusinessException("Plain business message");
                case "conflict" -> throw new ConflictException("Legacy conflict");
                case "conflict-coded" -> throw new ConflictException("SOME_CONFLICT", "Coded conflict");
                case "not-found" -> throw new ResourceNotFoundException("Thing", UUID.randomUUID());
                case "forbidden" -> throw new ForbiddenException("internal reason that must not leak");
                case "unauthorized" -> throw new UnauthorizedException("Authentication required");
                default -> throw new IllegalStateException("secret internal detail jdbc:postgresql://db/secret");
            }
        }
    }

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new ThrowingController())
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void businessExceptionKeepsStatusAndMessageAndExposesItsCode() throws Exception {
        mockMvc.perform(get("/test/business"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.status").value(422))
                .andExpect(jsonPath("$.error").value("Unprocessable Entity"))
                .andExpect(jsonPath("$.message").value("A rule was broken"))
                .andExpect(jsonPath("$.code").value("SOME_RULE"))
                .andExpect(jsonPath("$.path").value("/test/business"));
    }

    @Test
    void businessExceptionWithoutAnExplicitCodeUsesTheExistingDefault() throws Exception {
        mockMvc.perform(get("/test/business-default"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message").value("Plain business message"))
                .andExpect(jsonPath("$.code").value("BUSINESS_ERROR"));
    }

    @Test
    void legacyConflictExceptionStillWorksAndHasNoCode() throws Exception {
        mockMvc.perform(get("/test/conflict"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("Legacy conflict"))
                .andExpect(jsonPath("$.code").doesNotExist());
    }

    @Test
    void codedConflictExceptionExposesItsCode() throws Exception {
        mockMvc.perform(get("/test/conflict-coded"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("Coded conflict"))
                .andExpect(jsonPath("$.code").value("SOME_CONFLICT"));
    }

    @Test
    void otherStatusesAreUnchanged() throws Exception {
        mockMvc.perform(get("/test/not-found")).andExpect(status().isNotFound());
        mockMvc.perform(get("/test/unauthorized")).andExpect(status().isUnauthorized());
    }

    @Test
    void forbiddenNeverLeaksItsInternalReason() throws Exception {
        mockMvc.perform(get("/test/forbidden"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("Access denied"))
                .andExpect(content().string(not(containsString("internal reason"))));
    }

    @Test
    void unexpectedErrorsReturnAGenericMessageWithoutDetailsOrStackTrace() throws Exception {
        mockMvc.perform(get("/test/anything-else"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.message").value("An unexpected error occurred"))
                .andExpect(jsonPath("$.code").doesNotExist())
                .andExpect(content().string(not(containsString("secret"))))
                .andExpect(content().string(not(containsString("jdbc"))))
                .andExpect(content().string(not(containsString("at rw."))));
    }
}
