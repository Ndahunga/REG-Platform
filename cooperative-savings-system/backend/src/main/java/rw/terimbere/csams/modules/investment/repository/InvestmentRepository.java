package rw.terimbere.csams.modules.investment.repository;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import rw.terimbere.csams.modules.investment.entity.Investment;
import rw.terimbere.csams.modules.investment.entity.InvestmentStatus;

public interface InvestmentRepository extends JpaRepository<Investment, UUID> {

    Optional<Investment> findByIdAndCooperativeId(UUID id, UUID cooperativeId);

    Page<Investment> findByCooperativeId(UUID cooperativeId, Pageable pageable);

    Page<Investment> findByCooperativeIdAndStatus(
            UUID cooperativeId, InvestmentStatus status, Pageable pageable);

    List<Investment> findByCooperativeIdAndNameIgnoreCase(UUID cooperativeId, String name);

    long countByCooperativeIdAndStatusIn(UUID cooperativeId, Collection<InvestmentStatus> statuses);

    @Query(
            """
            SELECT COALESCE(SUM(i.remainingCapital), 0)
            FROM Investment i
            WHERE i.cooperativeId = :cooperativeId
              AND i.status IN :statuses
            """)
    BigDecimal sumRemainingCapitalByStatuses(
            @Param("cooperativeId") UUID cooperativeId,
            @Param("statuses") Collection<InvestmentStatus> statuses);

    @Query(
            """
            SELECT COALESCE(SUM(i.totalProfitReturned), 0)
            FROM Investment i
            WHERE i.cooperativeId = :cooperativeId
              AND i.status IN :statuses
            """)
    BigDecimal sumTotalProfitReturnedByStatuses(
            @Param("cooperativeId") UUID cooperativeId,
            @Param("statuses") Collection<InvestmentStatus> statuses);

    /**
     * Capital deployed in an Instant half-open window (cooperative-timezone month bounds).
     * Columns: capitalDeployed (SUM amount), investmentCount.
     */
    @Query(
            """
            SELECT COALESCE(SUM(i.amount), 0), COUNT(i)
            FROM Investment i
            WHERE i.cooperativeId = :cooperativeId
              AND i.activatedAt IS NOT NULL
              AND i.activatedAt >= :fromInclusive
              AND i.activatedAt < :toExclusive
              AND i.status IN :statuses
            """)
    List<Object[]> sumAmountAndCountActivatedBetween(
            @Param("cooperativeId") UUID cooperativeId,
            @Param("fromInclusive") Instant fromInclusive,
            @Param("toExclusive") Instant toExclusive,
            @Param("statuses") Collection<InvestmentStatus> statuses);

    /**
     * Largest active investments by remaining capital. Columns: id, name, amount,
     * remainingCapital, totalProfitReturned, status.
     */
    @Query(
            """
            SELECT i.id, i.name, i.amount, i.remainingCapital, i.totalProfitReturned, i.status
            FROM Investment i
            WHERE i.cooperativeId = :cooperativeId
              AND i.status IN :statuses
            ORDER BY i.remainingCapital DESC
            """)
    List<Object[]> findLargestByRemainingCapital(
            @Param("cooperativeId") UUID cooperativeId,
            @Param("statuses") Collection<InvestmentStatus> statuses,
            Pageable pageable);
}
