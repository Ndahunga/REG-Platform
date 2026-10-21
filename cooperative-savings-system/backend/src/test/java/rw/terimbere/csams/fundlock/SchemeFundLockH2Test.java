package rw.terimbere.csams.fundlock;

/**
 * The scheme-fund-lock scenarios on H2 (PostgreSQL mode), part of the normal suite. They exercise the real services,
 * endpoints and {@code SELECT ... FOR UPDATE} row locking as H2 implements it. H2's locking and MVCC differ from
 * PostgreSQL's, so a pass here is NOT PostgreSQL verification; {@code SchemeFundLockPostgresIT} runs the same scenarios
 * on PostgreSQL where Docker is available.
 */
class SchemeFundLockH2Test extends SchemeFundLockScenarios {

    /** Kept below H2's default lock wait (about two seconds) so the waiting operation never times out. */
    @Override
    protected long lockWaitObservationMillis() {
        return 700;
    }

    /** H2 cannot show lock waiters; the scenarios rely on the funds-read counter and the final records instead. */
    @Override
    protected Boolean lockWaiterVisibleInDatabase() {
        return null;
    }
}
