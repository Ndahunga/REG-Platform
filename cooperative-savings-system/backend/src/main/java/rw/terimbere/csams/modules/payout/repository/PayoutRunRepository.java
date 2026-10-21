package rw.terimbere.csams.modules.payout.repository;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import rw.terimbere.csams.modules.payout.entity.PayoutRun;
import rw.terimbere.csams.modules.payout.entity.PayoutRunStatus;

public interface PayoutRunRepository extends JpaRepository<PayoutRun, UUID> {

    Optional<PayoutRun> findByIdAndCooperativeId(UUID id, UUID cooperativeId);

    Page<PayoutRun> findByCooperativeId(UUID cooperativeId, Pageable pageable);

    Page<PayoutRun> findByCooperativeIdAndStatus(
            UUID cooperativeId, PayoutRunStatus status, Pageable pageable);

    java.util.List<PayoutRun> findByCooperativeIdAndPeriodFromAndPeriodTo(
            UUID cooperativeId, java.time.LocalDate periodFrom, java.time.LocalDate periodTo);

    long countByCooperativeIdAndStatus(UUID cooperativeId, PayoutRunStatus status);

    long countByStatus(PayoutRunStatus status);

    @Query(
            """
            SELECT COALESCE(SUM(r.payoutPoolAmount), 0)
            FROM PayoutRun r
            WHERE r.cooperativeId = :cooperativeId
              AND r.status IN :statuses
            """)
    BigDecimal sumPoolByStatuses(
            @Param("cooperativeId") UUID cooperativeId,
            @Param("statuses") Collection<PayoutRunStatus> statuses);
}
