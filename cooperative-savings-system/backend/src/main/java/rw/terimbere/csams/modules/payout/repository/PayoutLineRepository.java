package rw.terimbere.csams.modules.payout.repository;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import rw.terimbere.csams.modules.payout.entity.PayoutLine;
import rw.terimbere.csams.modules.payout.entity.PayoutLineStatus;

public interface PayoutLineRepository extends JpaRepository<PayoutLine, UUID> {

    List<PayoutLine> findByPayoutRunIdOrderByMemberUserIdAsc(UUID payoutRunId);

    List<PayoutLine> findByPayoutRunIdAndCooperativeIdOrderByMemberUserIdAsc(
            UUID payoutRunId, UUID cooperativeId);

    Optional<PayoutLine> findByIdAndPayoutRunIdAndCooperativeId(
            UUID id, UUID payoutRunId, UUID cooperativeId);

    List<PayoutLine> findByCooperativeIdAndMemberUserIdOrderByCreatedAtDesc(
            UUID cooperativeId, UUID memberUserId);

    List<PayoutLine> findByCooperativeIdAndPayoutRunIdAndMemberUserIdAndPayoutAmount(
            UUID cooperativeId, UUID payoutRunId, UUID memberUserId, BigDecimal payoutAmount);

    List<PayoutLine> findTop20ByCooperativeIdAndMemberUserIdOrderByCreatedAtDesc(
            UUID cooperativeId, UUID memberUserId);

    List<PayoutLine> findByPayoutRunIdAndIdIn(UUID payoutRunId, Collection<UUID> ids);

    long countByPayoutRunIdAndStatusNot(
            UUID payoutRunId, rw.terimbere.csams.modules.payout.entity.PayoutLineStatus status);

    @Query(
            """
            SELECT COALESCE(SUM(l.payoutAmount), 0)
            FROM PayoutLine l
            WHERE l.cooperativeId = :cooperativeId
              AND l.memberUserId = :memberUserId
              AND l.status IN :statuses
            """)
    BigDecimal sumPayoutAmountByMemberAndStatuses(
            @Param("cooperativeId") UUID cooperativeId,
            @Param("memberUserId") UUID memberUserId,
            @Param("statuses") Collection<PayoutLineStatus> statuses);
}
