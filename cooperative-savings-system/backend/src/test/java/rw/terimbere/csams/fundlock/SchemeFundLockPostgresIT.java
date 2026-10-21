package rw.terimbere.csams.fundlock;

import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * The scheme-fund-lock scenarios on a real PostgreSQL (Testcontainers). Opt-in: the name ends in {@code IT}, which
 * Surefire's default includes do not match, and the project has no Failsafe plugin, so {@code mvn test} never runs it.
 * It needs a Docker-compatible container runtime; Testcontainers fails loudly when none exists, on purpose, so a
 * missing runtime can never be mistaken for a pass.
 *
 * <pre>
 * mvn -B test -Dtest=SchemeFundLockPostgresIT -Dsurefire.failIfNoSpecifiedTests=false
 * </pre>
 *
 * <p>Setup: one disposable {@code postgres:16-alpine} per JVM with a random password; the real PostgreSQL driver;
 * the existing Flyway migrations (V1 through V29) applied by the application at start; Hibernate in {@code validate}
 * mode; test-only Hibernate batching mirroring {@code application-production.yml}. Everything else comes from the
 * existing {@code test} profile. Nothing touches any other database.
 *
 * <p>On PostgreSQL the held-pair scenarios also require the second operation to be visible in
 * {@code pg_stat_activity} as waiting on a {@code Lock}, which is positive proof that it is queued on the scheme fund
 * lock rather than merely slow.
 */
@Tag("postgres")
class SchemeFundLockPostgresIT extends SchemeFundLockScenarios {

    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"))
            .withDatabaseName("csams_fund_lock")
            .withUsername("csams_test")
            .withPassword(UUID.randomUUID().toString());

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void postgresProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        registry.add("spring.jpa.properties.hibernate.order_inserts", () -> "true");
        registry.add("spring.jpa.properties.hibernate.order_updates", () -> "true");
        registry.add("spring.jpa.properties.hibernate.jdbc.batch_size", () -> "50");
    }

    @Override
    protected long lockWaitObservationMillis() {
        return 15_000;
    }

    @Override
    protected Boolean lockWaiterVisibleInDatabase() {
        return count(
                        "select count(*) from pg_stat_activity where datname = current_database() "
                                + "and wait_event_type = 'Lock' and query ilike '%scheme_fund_locks%'")
                > 0;
    }
}
