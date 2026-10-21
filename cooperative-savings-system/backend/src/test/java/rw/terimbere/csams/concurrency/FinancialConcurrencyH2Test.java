package rw.terimbere.csams.concurrency;

/**
 * The financial concurrency scenarios on H2 (PostgreSQL mode), part of the normal suite. H2's locking differs from
 * PostgreSQL's, so a pass here is NOT PostgreSQL verification: see {@code FinancialConcurrencyPostgresIT}.
 */
class FinancialConcurrencyH2Test extends FinancialConcurrencyScenarios {}
