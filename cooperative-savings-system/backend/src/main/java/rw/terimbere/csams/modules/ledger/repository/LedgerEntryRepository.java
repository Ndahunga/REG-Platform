package rw.terimbere.csams.modules.ledger.repository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import rw.terimbere.csams.modules.ledger.entity.LedgerEntry;
import rw.terimbere.csams.modules.ledger.entity.LedgerEntryStatus;
import rw.terimbere.csams.shared.financial.LedgerTransactionType;

public interface LedgerEntryRepository
        extends JpaRepository<LedgerEntry, UUID>, JpaSpecificationExecutor<LedgerEntry> {

    long countByCooperativeId(UUID cooperativeId);

    boolean existsByIdempotencyKey(String idempotencyKey);

    /**
     * The date of the earliest ledger entry that is NOT a historical import (those carry the HISTIMP key prefix), or
     * null when the scheme has none. A historical import must end before it, so history is never counted twice.
     */
    @Query("select min(e.transactionDate) from LedgerEntry e where e.cooperativeId = :cooperativeId"
            + " and e.idempotencyKey not like 'HISTIMP:%'")
    java.time.LocalDate earliestLiveTransactionDate(@Param("cooperativeId") UUID cooperativeId);

    Optional<LedgerEntry> findByIdempotencyKey(String idempotencyKey);

    Optional<LedgerEntry> findByIdAndCooperativeId(UUID id, UUID cooperativeId);

    /**
     * Selection rule for HISTORICAL (period-accurate) reporting, alias {@code e}, parameter
     * {@code :cooperativeId}. Operational balances (available funds, contribution balances, ...) keep using the
     * APPROVED-only queries above and must never use this predicate.
     *
     * <p>A movement is reported when it is one of:
     * <ol>
     *   <li>an APPROVED entry that is not a REVERSAL;</li>
     *   <li>a REVERSED original that has an APPROVED REVERSAL referencing it for the same amount: the original
     *       stays visible in its own transaction-date period;</li>
     *   <li>an APPROVED REVERSAL whose referenced original exists in the same cooperative, is REVERSED and has
     *       the same amount, and which is the earliest such reversal of that original (ties on the creation instant are
     *       broken by id so the choice is deterministic): the offsetting movement
     *       appears in the reversal's own transaction-date period.</li>
     * </ol>
     * A REVERSED original without a valid reversal, a REVERSAL without a valid original, an amount mismatch, or a
     * second reversal of the same original is NOT reported: no movement is invented and no unexplained debit or
     * credit is counted. Such rows are data anomalies to be repaired, not reported around.
     */
    String PERIOD_ACCURATE_MOVEMENT =
            """
            \se.cooperativeId = :cooperativeId
              AND (
                    (e.status = rw.terimbere.csams.modules.ledger.entity.LedgerEntryStatus.APPROVED
                     AND e.transactionType <> rw.terimbere.csams.shared.financial.LedgerTransactionType.REVERSAL)
                 OR (e.status = rw.terimbere.csams.modules.ledger.entity.LedgerEntryStatus.REVERSED
                     AND EXISTS (SELECT 1 FROM LedgerEntry r
                                 WHERE r.reversesEntryId = e.id
                                   AND r.cooperativeId = e.cooperativeId
                                   AND r.transactionType = rw.terimbere.csams.shared.financial.LedgerTransactionType.REVERSAL
                                   AND r.status = rw.terimbere.csams.modules.ledger.entity.LedgerEntryStatus.APPROVED
                                   AND r.debitAmount = e.creditAmount))
                 OR (e.status = rw.terimbere.csams.modules.ledger.entity.LedgerEntryStatus.APPROVED
                     AND e.transactionType = rw.terimbere.csams.shared.financial.LedgerTransactionType.REVERSAL
                     AND EXISTS (SELECT 1 FROM LedgerEntry o
                                 WHERE o.id = e.reversesEntryId
                                   AND o.cooperativeId = e.cooperativeId
                                   AND o.status = rw.terimbere.csams.modules.ledger.entity.LedgerEntryStatus.REVERSED
                                   AND o.creditAmount = e.debitAmount)
                     AND NOT EXISTS (SELECT 1 FROM LedgerEntry r2
                                     WHERE r2.reversesEntryId = e.reversesEntryId
                                       AND r2.cooperativeId = e.cooperativeId
                                       AND r2.transactionType = rw.terimbere.csams.shared.financial.LedgerTransactionType.REVERSAL
                                       AND r2.status = rw.terimbere.csams.modules.ledger.entity.LedgerEntryStatus.APPROVED
                                       AND (r2.createdAt < e.createdAt
                                            OR (r2.createdAt = e.createdAt AND r2.id < e.id))))
              )
            """;

    Optional<LedgerEntry> findFirstBySourceEntityTypeAndSourceEntityIdAndTransactionTypeAndStatusOrderByCreatedAtDesc(
            String sourceEntityType,
            UUID sourceEntityId,
            LedgerTransactionType transactionType,
            LedgerEntryStatus status);

    List<LedgerEntry> findBySourceEntityTypeAndSourceEntityIdAndStatus(
            String sourceEntityType, UUID sourceEntityId, LedgerEntryStatus status);

    default Page<LedgerEntry> findFiltered(
            UUID cooperativeId,
            LedgerTransactionType transactionType,
            LocalDate fromDate,
            LocalDate toDate,
            UUID memberUserId,
            String sourceEntityType,
            Pageable pageable) {
        Pageable page = pageable == null ? Pageable.unpaged() : pageable;
        return findAll(
                LedgerEntrySpecs.filtered(
                        cooperativeId, transactionType, fromDate, toDate, memberUserId, sourceEntityType),
                page);
    }

    /** The live (not reversed) contribution cash entries of a scheme: what the split reconciliation checks. */
    @Query(
            """
            SELECT e FROM LedgerEntry e
            WHERE e.cooperativeId = :cooperativeId
              AND e.sourceEntityType = 'CONTRIBUTION'
              AND e.transactionType = rw.terimbere.csams.shared.financial.LedgerTransactionType.REGULAR_CONTRIBUTION
              AND e.status = rw.terimbere.csams.modules.ledger.entity.LedgerEntryStatus.APPROVED
            """)
    List<LedgerEntry> findLiveContributionCash(@Param("cooperativeId") UUID cooperativeId);

    @Query(
            """
            SELECT COALESCE(SUM(e.creditAmount), 0)
            FROM LedgerEntry e
            WHERE e.cooperativeId = :cooperativeId
              AND e.status = rw.terimbere.csams.modules.ledger.entity.LedgerEntryStatus.APPROVED
              AND e.transactionType IN :types
            """)
    BigDecimal sumApprovedCredits(
            @Param("cooperativeId") UUID cooperativeId,
            @Param("types") Collection<LedgerTransactionType> types);

    @Query(
            """
            SELECT COALESCE(SUM(e.debitAmount), 0)
            FROM LedgerEntry e
            WHERE e.cooperativeId = :cooperativeId
              AND e.status = rw.terimbere.csams.modules.ledger.entity.LedgerEntryStatus.APPROVED
              AND e.transactionType IN :types
            """)
    BigDecimal sumApprovedDebits(
            @Param("cooperativeId") UUID cooperativeId,
            @Param("types") Collection<LedgerTransactionType> types);

    /**
     * Period-accurate (historical) totals by type, inclusive transactionDate range, for reports.
     * See {@link #PERIOD_ACCURATE_MOVEMENT}. Columns: transactionType, creditSum, debitSum.
     */
    @Query(
            """
            SELECT e.transactionType,
                   COALESCE(SUM(e.creditAmount), 0),
                   COALESCE(SUM(e.debitAmount), 0)
            FROM LedgerEntry e
            WHERE """
                    + PERIOD_ACCURATE_MOVEMENT
                    + """
              AND e.transactionDate >= :fromDate
              AND e.transactionDate <= :toDate
              AND e.transactionType NOT IN :excludedTypes
            GROUP BY e.transactionType
            ORDER BY e.transactionType
            """)
    List<Object[]> sumMovementByTypeInPeriod(
            @Param("cooperativeId") UUID cooperativeId,
            @Param("fromDate") LocalDate fromDate,
            @Param("toDate") LocalDate toDate,
            @Param("excludedTypes") Collection<LedgerTransactionType> excludedTypes);

    @Query(
            """
            SELECT COALESCE(SUM(e.creditAmount), 0)
            FROM LedgerEntry e
            WHERE e.cooperativeId = :cooperativeId
              AND e.status = rw.terimbere.csams.modules.ledger.entity.LedgerEntryStatus.APPROVED
              AND e.transactionDate >= :fromDate
              AND e.transactionDate <= :toDate
              AND e.transactionType IN :types
            """)
    BigDecimal sumApprovedCreditsInPeriod(
            @Param("cooperativeId") UUID cooperativeId,
            @Param("fromDate") LocalDate fromDate,
            @Param("toDate") LocalDate toDate,
            @Param("types") Collection<LedgerTransactionType> types);

    @Query(
            """
            SELECT COALESCE(SUM(e.debitAmount), 0)
            FROM LedgerEntry e
            WHERE e.cooperativeId = :cooperativeId
              AND e.status = rw.terimbere.csams.modules.ledger.entity.LedgerEntryStatus.APPROVED
              AND e.transactionDate >= :fromDate
              AND e.transactionDate <= :toDate
              AND e.transactionType IN :types
            """)
    BigDecimal sumApprovedDebitsInPeriod(
            @Param("cooperativeId") UUID cooperativeId,
            @Param("fromDate") LocalDate fromDate,
            @Param("toDate") LocalDate toDate,
            @Param("types") Collection<LedgerTransactionType> types);

    /**
     * Period-accurate net movement (credits − debits) of main-fund types with transactionDate strictly before
     * {@code beforeDate}. See {@link #PERIOD_ACCURATE_MOVEMENT}.
     */
    @Query(
            """
            SELECT COALESCE(SUM(e.creditAmount), 0) - COALESCE(SUM(e.debitAmount), 0)
            FROM LedgerEntry e
            WHERE """
                    + PERIOD_ACCURATE_MOVEMENT
                    + """
              AND e.transactionDate < :beforeDate
              AND e.transactionType NOT IN :excludedTypes
            """)
    BigDecimal sumNetMovementBefore(
            @Param("cooperativeId") UUID cooperativeId,
            @Param("beforeDate") LocalDate beforeDate,
            @Param("excludedTypes") Collection<LedgerTransactionType> excludedTypes);

    /**
     * Period-accurate net movement (credits − debits) of main-fund types with transactionDate on or before
     * {@code throughDate}. See {@link #PERIOD_ACCURATE_MOVEMENT}.
     */
    @Query(
            """
            SELECT COALESCE(SUM(e.creditAmount), 0) - COALESCE(SUM(e.debitAmount), 0)
            FROM LedgerEntry e
            WHERE """
                    + PERIOD_ACCURATE_MOVEMENT
                    + """
              AND e.transactionDate <= :throughDate
              AND e.transactionType NOT IN :excludedTypes
            """)
    BigDecimal sumNetMovementThrough(
            @Param("cooperativeId") UUID cooperativeId,
            @Param("throughDate") LocalDate throughDate,
            @Param("excludedTypes") Collection<LedgerTransactionType> excludedTypes);

    @Query(
            """
            SELECT COUNT(e) FROM LedgerEntry e
            WHERE e.cooperativeId = :cooperativeId
              AND e.transactionDate <= :date
              AND e.transactionType NOT IN :excluded
            """)
    long countActivityOnOrBefore(
            @Param("cooperativeId") UUID cooperativeId,
            @Param("date") LocalDate date,
            @Param("excluded") Collection<LedgerTransactionType> excluded);
}
