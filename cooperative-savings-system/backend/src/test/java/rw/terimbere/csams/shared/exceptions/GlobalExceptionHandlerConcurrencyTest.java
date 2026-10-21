package rw.terimbere.csams.shared.exceptions;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.persistence.OptimisticLockException;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.orm.jpa.JpaOptimisticLockingFailureException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * Exception-to-HTTP mapping for optimistic-lock conflicts. These are simulated exceptions on a standalone MockMvc:
 * they prove the response contract only, not database concurrency. Real {@code @Version} conflicts against the
 * database are covered by {@code OptimisticLockConflictIntegrationTest}.
 */
class GlobalExceptionHandlerConcurrencyTest {

    private static final String SAFE_MESSAGE = "The record was modified by another request. Refresh the data and try again.";
    private static final UUID ENTITY_ID = UUID.fromString("0a1b2c3d-1111-2222-3333-444455556666");

    private MockMvc mockMvc;

    @RestController
    static class ThrowingController {
        @GetMapping("/test/{kind}")
        String boom(@PathVariable String kind) {
            switch (kind) {
                case "object-lock" -> throw new ObjectOptimisticLockingFailureException(
                        "rw.terimbere.csams.modules.loan.entity.Loan", ENTITY_ID);
                case "general-lock" -> throw new OptimisticLockingFailureException(
                        "update loans set outstanding_principal=? where id=? and version=? jdbc:postgresql://db/secret");
                case "jpa-lock" -> throw new JpaOptimisticLockingFailureException(
                        new OptimisticLockException("Row was updated or deleted by another transaction " + ENTITY_ID));
                case "jakarta-lock" -> throw new OptimisticLockException("raw jakarta " + ENTITY_ID);
                case "coded-conflict" -> throw new ConflictException("SOME_CONFLICT", "Coded conflict");
                case "legacy-conflict" -> throw new ConflictException("Legacy conflict");
                case "integrity" -> throw new DataIntegrityViolationException(
                        "duplicate key value violates unique constraint uq_secret");
                case "pessimistic" -> throw new CannotAcquireLockException("could not obtain lock on row in loans");
                case "denied" -> throw new AccessDeniedException("internal denial reason");
                case "forbidden" -> throw new ForbiddenException("internal reason that must not leak");
                case "business" -> throw new BusinessException("SOME_RULE", "A rule was broken");
                case "validation" -> throw new ValidationException("Bad input");
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

    // ----------------------------------------------------------------- Test A

    @Test
    void anObjectOptimisticLockFailureIs409WithTheConcurrentUpdateCode() throws Exception {
        mockMvc.perform(get("/test/object-lock"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.error").value("Conflict"))
                .andExpect(jsonPath("$.code").value("CONCURRENT_UPDATE"))
                .andExpect(jsonPath("$.message").value(SAFE_MESSAGE))
                .andExpect(jsonPath("$.path").value("/test/object-lock"))
                .andExpect(jsonPath("$.timestamp").exists())
                .andExpect(jsonPath("$.fieldErrors").doesNotExist());
    }

    // ----------------------------------------------------------------- Test B

    @Test
    void theGeneralSpringOptimisticLockTypesAndWrappersAreHandledToo() throws Exception {
        for (String kind : new String[] {"general-lock", "jpa-lock"}) {
            mockMvc.perform(get("/test/" + kind))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("CONCURRENT_UPDATE"))
                    .andExpect(jsonPath("$.message").value(SAFE_MESSAGE));
        }
    }

    // ----------------------------------------------------------------- Test C

    @Test
    void aRawJakartaOptimisticLockExceptionIsNotMappedBecauseSpringTranslatesItFirst() throws Exception {
        // Spring's repository and transaction translation turns Jakarta's exception into a Spring one before it can
        // reach the controller (proved against the real database in OptimisticLockConflictIntegrationTest), so no
        // dedicated handler exists. A raw one that somehow escaped would stay an unexpected error, not a 409.
        mockMvc.perform(get("/test/jakarta-lock"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").doesNotExist())
                .andExpect(jsonPath("$.message").value("An unexpected error occurred"));
    }

    // ----------------------------------------------------------------- Test D

    @Test
    void applicationConflictsKeepTheirStatusAndCodes() throws Exception {
        mockMvc.perform(get("/test/coded-conflict"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SOME_CONFLICT"))
                .andExpect(jsonPath("$.message").value("Coded conflict"));
        mockMvc.perform(get("/test/legacy-conflict"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").doesNotExist())
                .andExpect(jsonPath("$.message").value("Legacy conflict"));
    }

    // ----------------------------------------------------------------- Test E

    @Test
    void unrelatedPersistenceFailuresAreNotLabelledConcurrentUpdate() throws Exception {
        for (String kind : new String[] {"integrity", "pessimistic"}) {
            mockMvc.perform(get("/test/" + kind))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.code").doesNotExist())
                    .andExpect(jsonPath("$.message").value("An unexpected error occurred"))
                    .andExpect(content().string(not(containsString("CONCURRENT_UPDATE"))));
        }
    }

    // ----------------------------------------------------------------- Tests F, G, H

    @Test
    void authorizationBusinessValidationAndUnexpectedFailuresAreUnchanged() throws Exception {
        mockMvc.perform(get("/test/denied"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("Access denied"));
        mockMvc.perform(get("/test/forbidden")).andExpect(status().isForbidden());

        mockMvc.perform(get("/test/business"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("SOME_RULE"))
                .andExpect(jsonPath("$.message").value("A rule was broken"));
        mockMvc.perform(get("/test/validation"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Bad input"));

        mockMvc.perform(get("/test/anything-else"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").doesNotExist())
                .andExpect(jsonPath("$.message").value("An unexpected error occurred"));
    }

    // ----------------------------------------------------------------- Test I

    @Test
    void theConflictResponseLeaksNoSqlEntityIdentifierOrStackTrace() throws Exception {
        for (String kind : new String[] {"object-lock", "general-lock", "jpa-lock"}) {
            mockMvc.perform(get("/test/" + kind))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.details").doesNotExist())
                    .andExpect(content().string(not(containsString(ENTITY_ID.toString()))))
                    .andExpect(content().string(not(containsString("Loan"))))
                    .andExpect(content().string(not(containsString("rw.terimbere"))))
                    .andExpect(content().string(not(containsString("update loans"))))
                    .andExpect(content().string(not(containsString("jdbc"))))
                    .andExpect(content().string(not(containsString("Row was updated"))))
                    .andExpect(content().string(not(containsString("at rw."))))
                    .andExpect(content().string(not(containsString("Exception"))));
        }
    }
}
