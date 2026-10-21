package rw.terimbere.csams.modules.loan.repository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import rw.terimbere.csams.modules.loan.entity.Loan;
import rw.terimbere.csams.modules.loan.entity.LoanStatus;
import rw.terimbere.csams.modules.loan.support.LoanOverdueRules;

public interface LoanRepository extends JpaRepository<Loan, UUID> {

    Optional<Loan> findByIdAndCooperativeId(UUID id, UUID cooperativeId);

    /** Row-locking lookup for every path that applies a repayment, so two repayments cannot overpay one loan. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select l from Loan l where l.id = :id and l.cooperativeId = :cooperativeId")
    Optional<Loan> findByIdAndCooperativeIdForUpdate(
            @Param("id") UUID id, @Param("cooperativeId") UUID cooperativeId);

    Page<Loan> findByCooperativeId(UUID cooperativeId, Pageable pageable);

    Page<Loan> findByCooperativeIdAndStatus(UUID cooperativeId, LoanStatus status, Pageable pageable);

    Page<Loan> findByCooperativeIdAndStatusIn(
            UUID cooperativeId, Collection<LoanStatus> statuses, Pageable pageable);

    Page<Loan> findByCooperativeIdAndMemberUserId(UUID cooperativeId, UUID memberUserId, Pageable pageable);

    Page<Loan> findByCooperativeIdAndMemberUserIdAndStatus(
            UUID cooperativeId, UUID memberUserId, LoanStatus status, Pageable pageable);

    List<Loan> findByCooperativeIdAndMemberUserIdOrderByRequestDateDescCreatedAtDesc(
            UUID cooperativeId, UUID memberUserId);

    List<Loan> findTop20ByCooperativeIdAndMemberUserIdOrderByRequestDateDescCreatedAtDesc(
            UUID cooperativeId, UUID memberUserId);

    List<Loan> findByCooperativeIdAndMemberUserIdAndStatusIn(
            UUID cooperativeId, UUID memberUserId, Collection<LoanStatus> statuses);

    boolean existsByCooperativeIdAndMemberUserIdAndStatusIn(
            UUID cooperativeId, UUID memberUserId, Collection<LoanStatus> statuses);

    List<Loan> findByCooperativeIdAndMemberUserIdAndDisbursementDate(
            UUID cooperativeId, UUID memberUserId, LocalDate disbursementDate);

    @Query(
            """
            SELECT COALESCE(SUM(l.outstandingPrincipal), 0)
            FROM Loan l
            WHERE l.cooperativeId = :cooperativeId
              AND l.status IN :statuses
            """)
    BigDecimal sumOutstandingPrincipalByStatuses(
            @Param("cooperativeId") UUID cooperativeId, @Param("statuses") Collection<LoanStatus> statuses);

    @Query(
            """
            SELECT COALESCE(SUM(l.outstandingInterest), 0)
            FROM Loan l
            WHERE l.cooperativeId = :cooperativeId
              AND l.status IN :statuses
            """)
    BigDecimal sumOutstandingInterestByStatuses(
            @Param("cooperativeId") UUID cooperativeId, @Param("statuses") Collection<LoanStatus> statuses);

    @Query(
            """
            SELECT COALESCE(SUM(l.principalAmount), 0)
            FROM Loan l
            WHERE l.cooperativeId = :cooperativeId
              AND l.status IN :statuses
              AND l.principalAmount IS NOT NULL
            """)
    BigDecimal sumPrincipalByStatuses(
            @Param("cooperativeId") UUID cooperativeId, @Param("statuses") Collection<LoanStatus> statuses);

    @Query(
            """
            SELECT COALESCE(SUM(l.principalAmount), 0)
            FROM Loan l
            WHERE l.cooperativeId = :cooperativeId
              AND l.memberUserId = :memberUserId
              AND l.status IN :statuses
              AND l.principalAmount IS NOT NULL
            """)
    BigDecimal sumPrincipalByMemberAndStatuses(
            @Param("cooperativeId") UUID cooperativeId,
            @Param("memberUserId") UUID memberUserId,
            @Param("statuses") Collection<LoanStatus> statuses);

    @Query(
            """
            SELECT COALESCE(SUM(l.outstandingPrincipal), 0)
            FROM Loan l
            WHERE l.cooperativeId = :cooperativeId
              AND l.memberUserId = :memberUserId
              AND l.status IN :statuses
            """)
    BigDecimal sumOutstandingPrincipalByMemberAndStatuses(
            @Param("cooperativeId") UUID cooperativeId,
            @Param("memberUserId") UUID memberUserId,
            @Param("statuses") Collection<LoanStatus> statuses);

    @Query(
            """
            SELECT COALESCE(SUM(l.outstandingInterest), 0)
            FROM Loan l
            WHERE l.cooperativeId = :cooperativeId
              AND l.memberUserId = :memberUserId
              AND l.status IN :statuses
            """)
    BigDecimal sumOutstandingInterestByMemberAndStatuses(
            @Param("cooperativeId") UUID cooperativeId,
            @Param("memberUserId") UUID memberUserId,
            @Param("statuses") Collection<LoanStatus> statuses);

    @Query(
            """
            SELECT COALESCE(SUM(l.totalRepaidPrincipal + l.totalRepaidInterest), 0)
            FROM Loan l
            WHERE l.cooperativeId = :cooperativeId
              AND l.memberUserId = :memberUserId
            """)
    BigDecimal sumTotalRepaidByMember(
            @Param("cooperativeId") UUID cooperativeId, @Param("memberUserId") UUID memberUserId);

    List<Loan> findByStatusIn(Collection<LoanStatus> statuses);

    long countByCooperativeIdAndStatus(UUID cooperativeId, LoanStatus status);

    long countByStatus(LoanStatus status);

    long countByCooperativeIdInAndStatus(Collection<UUID> cooperativeIds, LoanStatus status);

    long countByStatusAndFirstApprovedByNot(LoanStatus status, UUID firstApprovedBy);

    long countByCooperativeIdInAndStatusAndFirstApprovedByNot(
            Collection<UUID> cooperativeIds, LoanStatus status, UUID firstApprovedBy);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(
            "UPDATE Loan l"
                    + " SET l.status = rw.terimbere.csams.modules.loan.entity.LoanStatus.OVERDUE"
                    + " WHERE l.cooperativeId = :cooperativeId"
                    + " AND "
                    + LoanOverdueRules.ACTIVE_PAST_DUE_WITH_BALANCE)
    int markOverdue(@Param("cooperativeId") UUID cooperativeId, @Param("today") LocalDate today);

    /**
     * Read-only count of loans that are currently overdue per {@link LoanOverdueRules}.
     * Does not mutate {@code Loan.status}.
     */
    @Query(
            "SELECT COUNT(l)"
                    + " FROM Loan l"
                    + " WHERE l.cooperativeId = :cooperativeId"
                    + " AND "
                    + LoanOverdueRules.CURRENTLY_OVERDUE_FOR_ANALYTICS)
    long countCurrentlyOverdue(
            @Param("cooperativeId") UUID cooperativeId, @Param("today") LocalDate today);

    @Query(
            "SELECT l FROM Loan l"
                    + " WHERE l.cooperativeId = :cooperativeId"
                    + " AND "
                    + LoanOverdueRules.CURRENTLY_OVERDUE_FOR_ANALYTICS)
    Page<Loan> findCurrentlyOverdue(
            @Param("cooperativeId") UUID cooperativeId,
            @Param("today") LocalDate today,
            Pageable pageable);

    @Query(
            "SELECT l FROM Loan l"
                    + " WHERE l.cooperativeId = :cooperativeId"
                    + " AND l.memberUserId = :memberUserId"
                    + " AND "
                    + LoanOverdueRules.CURRENTLY_OVERDUE_FOR_ANALYTICS)
    Page<Loan> findCurrentlyOverdueByMember(
            @Param("cooperativeId") UUID cooperativeId,
            @Param("memberUserId") UUID memberUserId,
            @Param("today") LocalDate today,
            Pageable pageable);

    @Query(
            "SELECT l FROM Loan l"
                    + " WHERE l.cooperativeId = :cooperativeId"
                    + " AND "
                    + LoanOverdueRules.ACTIVE_NOT_CURRENTLY_OVERDUE)
    Page<Loan> findActiveNotCurrentlyOverdue(
            @Param("cooperativeId") UUID cooperativeId,
            @Param("today") LocalDate today,
            Pageable pageable);

    @Query(
            "SELECT l FROM Loan l"
                    + " WHERE l.cooperativeId = :cooperativeId"
                    + " AND l.memberUserId = :memberUserId"
                    + " AND "
                    + LoanOverdueRules.ACTIVE_NOT_CURRENTLY_OVERDUE)
    Page<Loan> findActiveNotCurrentlyOverdueByMember(
            @Param("cooperativeId") UUID cooperativeId,
            @Param("memberUserId") UUID memberUserId,
            @Param("today") LocalDate today,
            Pageable pageable);

    @Query(
            """
            SELECT COUNT(l)
            FROM Loan l
            WHERE l.cooperativeId = :cooperativeId
              AND l.openingImportId IS NULL
              AND l.disbursementDate IS NOT NULL
              AND l.disbursementDate >= :fromDate
              AND l.disbursementDate <= :toDate
              AND l.status IN :statuses
            """)
    long countDisbursedInDateRange(
            @Param("cooperativeId") UUID cooperativeId,
            @Param("fromDate") LocalDate fromDate,
            @Param("toDate") LocalDate toDate,
            @Param("statuses") Collection<LoanStatus> statuses);

    @Query(
            """
            SELECT COALESCE(SUM(l.principalAmount), 0)
            FROM Loan l
            WHERE l.cooperativeId = :cooperativeId
              AND l.openingImportId IS NULL
              AND l.disbursementDate IS NOT NULL
              AND l.disbursementDate >= :fromDate
              AND l.disbursementDate <= :toDate
              AND l.status IN :statuses
              AND l.principalAmount IS NOT NULL
            """)
    BigDecimal sumDisbursedPrincipalInDateRange(
            @Param("cooperativeId") UUID cooperativeId,
            @Param("fromDate") LocalDate fromDate,
            @Param("toDate") LocalDate toDate,
            @Param("statuses") Collection<LoanStatus> statuses);

    /**
     * Monthly disbursement seasonality. Columns: month (1–12), loanCount, principalAmount.
     *
     * <p>Uses {@code disbursementDate} only; callers must pass disbursed statuses and a year
     * calendar range.
     */
    @Query(
            """
            SELECT extract(month from l.disbursementDate),
                   COUNT(l),
                   COALESCE(SUM(l.principalAmount), 0)
            FROM Loan l
            WHERE l.cooperativeId = :cooperativeId
              AND l.openingImportId IS NULL
              AND l.disbursementDate IS NOT NULL
              AND l.disbursementDate >= :fromDate
              AND l.disbursementDate <= :toDate
              AND l.status IN :statuses
            GROUP BY extract(month from l.disbursementDate)
            ORDER BY extract(month from l.disbursementDate)
            """)
    List<Object[]> sumDisbursedGroupedByMonth(
            @Param("cooperativeId") UUID cooperativeId,
            @Param("fromDate") LocalDate fromDate,
            @Param("toDate") LocalDate toDate,
            @Param("statuses") Collection<LoanStatus> statuses);

    /**
     * Frequent borrowers (disbursed loans in date range). Columns: memberUserId, loanCount,
     * totalPrincipal.
     */
    @Query(
            """
            SELECT l.memberUserId,
                   COUNT(l),
                   COALESCE(SUM(l.principalAmount), 0)
            FROM Loan l
            WHERE l.cooperativeId = :cooperativeId
              AND l.openingImportId IS NULL
              AND l.disbursementDate IS NOT NULL
              AND l.disbursementDate >= :fromDate
              AND l.disbursementDate <= :toDate
              AND l.status IN :statuses
            GROUP BY l.memberUserId
            ORDER BY COUNT(l) DESC, COALESCE(SUM(l.principalAmount), 0) DESC
            """)
    List<Object[]> countDisbursedGroupedByMemberOrdered(
            @Param("cooperativeId") UUID cooperativeId,
            @Param("fromDate") LocalDate fromDate,
            @Param("toDate") LocalDate toDate,
            @Param("statuses") Collection<LoanStatus> statuses,
            Pageable pageable);

    /**
     * Overdue loan follow-up by member (read-only; does not mutate Loan.status).
     * Columns: memberUserId, overdueLoanCount, outstandingPrincipal, oldestDueDate
     *
     * <p>Uses {@link LoanOverdueRules#CURRENTLY_OVERDUE_FOR_ANALYTICS} so ACTIVE past-due loans
     * appear without calling {@code markOverdue}.
     */
    @Query(
            "SELECT l.memberUserId,"
                    + " COUNT(l),"
                    + " COALESCE(SUM(l.outstandingPrincipal), 0),"
                    + " MIN(l.dueDate)"
                    + " FROM Loan l"
                    + " WHERE l.cooperativeId = :cooperativeId"
                    + " AND "
                    + LoanOverdueRules.CURRENTLY_OVERDUE_FOR_ANALYTICS
                    + " GROUP BY l.memberUserId"
                    + " ORDER BY COALESCE(SUM(l.outstandingPrincipal), 0) DESC")
    List<Object[]> sumOverdueGroupedByMemberOrdered(
            @Param("cooperativeId") UUID cooperativeId,
            @Param("today") LocalDate today,
            Pageable pageable);

    /**
     * Outstanding principal of loans issued inside OuWealth. Loans imported by the opening-balance
     * migration are excluded: the explicit cutover cash is already net of them.
     */
    @Query(
            """
            SELECT COALESCE(SUM(l.outstandingPrincipal), 0)
            FROM Loan l
            WHERE l.cooperativeId = :cooperativeId
              AND l.openingImportId IS NULL
              AND l.status IN :statuses
            """)
    BigDecimal sumOutstandingPrincipalExcludingImported(
            @Param("cooperativeId") UUID cooperativeId, @Param("statuses") Collection<LoanStatus> statuses);
}
