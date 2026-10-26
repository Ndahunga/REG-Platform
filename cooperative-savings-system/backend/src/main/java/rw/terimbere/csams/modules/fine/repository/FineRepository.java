package rw.terimbere.csams.modules.fine.repository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import rw.terimbere.csams.modules.fine.entity.Fine;
import rw.terimbere.csams.modules.fine.entity.FineStatus;

public interface FineRepository extends JpaRepository<Fine, UUID> {

    Optional<Fine> findByIdAndCooperativeId(UUID id, UUID cooperativeId);

    Page<Fine> findByCooperativeId(UUID cooperativeId, Pageable pageable);

    Page<Fine> findByCooperativeIdAndStatus(UUID cooperativeId, FineStatus status, Pageable pageable);

    Page<Fine> findByCooperativeIdAndMemberUserId(UUID cooperativeId, UUID memberUserId, Pageable pageable);

    Page<Fine> findByCooperativeIdAndMemberUserIdAndStatus(
            UUID cooperativeId, UUID memberUserId, FineStatus status, Pageable pageable);

    List<Fine> findByCooperativeIdAndMemberUserIdOrderByIssuedDateDescCreatedAtDesc(
            UUID cooperativeId, UUID memberUserId);

    List<Fine> findByCooperativeIdAndMemberUserIdAndIssuedDate(
            UUID cooperativeId, UUID memberUserId, java.time.LocalDate issuedDate);

    List<Fine> findTop20ByCooperativeIdAndMemberUserIdOrderByIssuedDateDescCreatedAtDesc(
            UUID cooperativeId, UUID memberUserId);

    boolean existsByCooperativeIdAndSourceContributionId(UUID cooperativeId, UUID sourceContributionId);

    boolean existsByAutomaticSourceKey(String automaticSourceKey);

    List<Fine> findBySourceContributionId(UUID sourceContributionId);

    List<Fine> findBySourceLoanInstallmentIdAndStatusInOrderByIssuedDateAscCreatedAtAsc(
            UUID sourceLoanInstallmentId, Collection<FineStatus> statuses);

    long countByCooperativeId(UUID cooperativeId);

    long countByCooperativeIdAndStatusIn(UUID cooperativeId, Collection<FineStatus> statuses);

    @Query(
            """
            SELECT COUNT(DISTINCT f.memberUserId)
            FROM Fine f
            WHERE f.cooperativeId = :cooperativeId
              AND f.status IN :statuses
            """)
    long countDistinctMembersByStatusIn(
            @Param("cooperativeId") UUID cooperativeId, @Param("statuses") Collection<FineStatus> statuses);

    @Query(
            """
            SELECT COALESCE(SUM(f.totalAmount), 0)
            FROM Fine f
            WHERE f.cooperativeId = :cooperativeId
              AND f.status NOT IN (
                  rw.terimbere.csams.modules.fine.entity.FineStatus.CANCELLED
              )
            """)
    BigDecimal sumTotalAmountExcludingCancelled(@Param("cooperativeId") UUID cooperativeId);

    @Query(
            """
            SELECT COALESCE(SUM(f.totalAmount), 0)
            FROM Fine f
            WHERE f.cooperativeId = :cooperativeId
              AND f.memberUserId = :memberUserId
              AND f.status NOT IN (
                  rw.terimbere.csams.modules.fine.entity.FineStatus.CANCELLED
              )
            """)
    BigDecimal sumTotalAmountByMemberExcludingCancelled(
            @Param("cooperativeId") UUID cooperativeId, @Param("memberUserId") UUID memberUserId);

    @Query(
            """
            SELECT COALESCE(SUM(f.outstandingAmount), 0)
            FROM Fine f
            WHERE f.cooperativeId = :cooperativeId
              AND f.memberUserId = :memberUserId
              AND f.status IN :statuses
            """)
    BigDecimal sumOutstandingByMemberAndStatuses(
            @Param("cooperativeId") UUID cooperativeId,
            @Param("memberUserId") UUID memberUserId,
            @Param("statuses") Collection<FineStatus> statuses);

    @Query(
            """
            SELECT COALESCE(SUM(f.outstandingAmount), 0)
            FROM Fine f
            WHERE f.cooperativeId = :cooperativeId
              AND f.status IN :statuses
            """)
    BigDecimal sumOutstandingByStatuses(
            @Param("cooperativeId") UUID cooperativeId, @Param("statuses") Collection<FineStatus> statuses);

    /**
     * Fine receivables for share valuation (NAV): same as {@link #sumOutstandingByStatuses} but a penalty linked to a
     * {@code WRITTEN_OFF} loan of the same scheme is not a recoverable asset and is left out. Fines with no source loan
     * (contribution and manual fines) always count. This changes valuation eligibility only: no fine is waived,
     * cancelled or edited. The loan match is scheme-scoped, so a loan of another scheme can never exclude a fine.
     */
    @Query(
            """
            SELECT COALESCE(SUM(f.outstandingAmount), 0)
            FROM Fine f
            WHERE f.cooperativeId = :cooperativeId
              AND f.status IN :statuses
              AND NOT EXISTS (
                  SELECT 1
                  FROM Loan l
                  WHERE l.id = f.sourceLoanId
                    AND l.cooperativeId = f.cooperativeId
                    AND l.status = rw.terimbere.csams.modules.loan.entity.LoanStatus.WRITTEN_OFF
              )
            """)
    BigDecimal sumOutstandingForValuationExcludingWrittenOffLoans(
            @Param("cooperativeId") UUID cooperativeId, @Param("statuses") Collection<FineStatus> statuses);

    @Query(
            """
            SELECT COUNT(f)
            FROM Fine f
            WHERE f.cooperativeId = :cooperativeId
              AND f.issuedDate >= :fromDate
              AND f.issuedDate <= :toDate
              AND f.status NOT IN (
                  rw.terimbere.csams.modules.fine.entity.FineStatus.CANCELLED
              )
            """)
    long countIssuedInDateRange(
            @Param("cooperativeId") UUID cooperativeId,
            @Param("fromDate") LocalDate fromDate,
            @Param("toDate") LocalDate toDate);

    @Query(
            """
            SELECT COALESCE(SUM(f.totalAmount), 0)
            FROM Fine f
            WHERE f.cooperativeId = :cooperativeId
              AND f.issuedDate >= :fromDate
              AND f.issuedDate <= :toDate
              AND f.status NOT IN (
                  rw.terimbere.csams.modules.fine.entity.FineStatus.CANCELLED
              )
            """)
    BigDecimal sumIssuedAmountInDateRange(
            @Param("cooperativeId") UUID cooperativeId,
            @Param("fromDate") LocalDate fromDate,
            @Param("toDate") LocalDate toDate);

    /**
     * YTD fine follow-up ranking by authoritative outstanding balance.
     *
     * <p>Only UNPAID / PARTIALLY_PAID fines (still-owed statuses used by FineService unpaid counts).
     * Excludes PAID, WAIVED, and CANCELLED so waived/cancelled obligations never rank as outstanding.
     * Columns: memberUserId, issuedAmount, paidAmount, outstandingAmount, fineCount
     */
    @Query(
            """
            SELECT f.memberUserId,
                   COALESCE(SUM(f.totalAmount), 0),
                   COALESCE(SUM(f.paidAmount), 0),
                   COALESCE(SUM(f.outstandingAmount), 0),
                   COUNT(f)
            FROM Fine f
            WHERE f.cooperativeId = :cooperativeId
              AND f.issuedDate >= :fromDate
              AND f.issuedDate <= :toDate
              AND f.status IN (
                  rw.terimbere.csams.modules.fine.entity.FineStatus.UNPAID,
                  rw.terimbere.csams.modules.fine.entity.FineStatus.PARTIALLY_PAID
              )
              AND f.outstandingAmount > 0
            GROUP BY f.memberUserId
            HAVING COALESCE(SUM(f.outstandingAmount), 0) > 0
            ORDER BY COALESCE(SUM(f.outstandingAmount), 0) DESC
            """)
    List<Object[]> sumFineFollowUpGroupedByMemberOrdered(
            @Param("cooperativeId") UUID cooperativeId,
            @Param("fromDate") LocalDate fromDate,
            @Param("toDate") LocalDate toDate,
            Pageable pageable);
}
