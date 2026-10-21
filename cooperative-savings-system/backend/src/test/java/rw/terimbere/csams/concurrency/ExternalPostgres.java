package rw.terimbere.csams.concurrency;

import org.springframework.test.context.DynamicPropertyRegistry;

/**
 * Points a Spring test at a disposable PostgreSQL that the developer started (no Docker needed). The location comes from
 * system properties and the tests fail loudly when it is missing, so a missing database can never look like a pass:
 *
 * <pre>
 * mvn test -Dtest='FinancialConcurrencyPostgresIT,SchemeFundLockExternalPostgresIT' \
 *   -Dsurefire.failIfNoSpecifiedTests=false \
 *   -Dcsams.pg.url=jdbc:postgresql://localhost:5432/csams_it -Dcsams.pg.user=csams_it -Dcsams.pg.password=...
 * </pre>
 *
 * Never point it at a database that holds real data: the tests create schemes, members and ledger entries.
 */
public final class ExternalPostgres {

    private ExternalPostgres() {}

    public static void register(DynamicPropertyRegistry registry) {
        String url = System.getProperty("csams.pg.url");
        if (url == null || url.isBlank()) {
            throw new IllegalStateException(
                    "Set -Dcsams.pg.url (and -Dcsams.pg.user / -Dcsams.pg.password) to a disposable PostgreSQL database");
        }
        registry.add("spring.datasource.url", () -> url);
        registry.add("spring.datasource.username", () -> System.getProperty("csams.pg.user", "postgres"));
        registry.add("spring.datasource.password", () -> System.getProperty("csams.pg.password", ""));
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        registry.add("spring.jpa.properties.hibernate.order_inserts", () -> "true");
        registry.add("spring.jpa.properties.hibernate.order_updates", () -> "true");
        registry.add("spring.jpa.properties.hibernate.jdbc.batch_size", () -> "50");
    }
}
