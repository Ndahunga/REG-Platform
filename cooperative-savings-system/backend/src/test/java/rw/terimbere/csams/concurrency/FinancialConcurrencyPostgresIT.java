package rw.terimbere.csams.concurrency;

import org.junit.jupiter.api.Tag;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * The financial concurrency scenarios on a real PostgreSQL started by the developer (see {@link ExternalPostgres}).
 * Opt-in: the name ends in {@code IT}, so {@code mvn test} never runs it by default.
 */
@Tag("postgres")
class FinancialConcurrencyPostgresIT extends FinancialConcurrencyScenarios {

    @DynamicPropertySource
    static void postgres(DynamicPropertyRegistry registry) {
        ExternalPostgres.register(registry);
    }
}
