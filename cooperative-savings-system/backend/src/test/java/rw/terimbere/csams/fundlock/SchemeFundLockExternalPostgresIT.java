package rw.terimbere.csams.fundlock;

import org.junit.jupiter.api.Tag;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import rw.terimbere.csams.concurrency.ExternalPostgres;

/**
 * The scheme-fund-lock scenarios on a real PostgreSQL that the developer started (no Docker). Same scenarios and same
 * {@code pg_stat_activity} proof as {@code SchemeFundLockPostgresIT}; see {@link ExternalPostgres} for the properties.
 */
@Tag("postgres")
class SchemeFundLockExternalPostgresIT extends SchemeFundLockScenarios {

    @DynamicPropertySource
    static void postgres(DynamicPropertyRegistry registry) {
        ExternalPostgres.register(registry);
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
