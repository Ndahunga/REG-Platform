package rw.terimbere.csams.modules.share.repository;

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
import rw.terimbere.csams.modules.share.entity.SharePurchase;
import rw.terimbere.csams.modules.share.entity.SharePurchaseStatus;

public interface SharePurchaseRepository extends JpaRepository<SharePurchase, UUID> {

    Optional<SharePurchase> findByIdAndCooperativeId(UUID id, UUID cooperativeId);

    Page<SharePurchase> findByCooperativeIdAndStatusOrderByRequestedAtDesc(
            UUID cooperativeId, SharePurchaseStatus status, Pageable pageable);

    Page<SharePurchase> findByCooperativeIdAndMemberUserIdOrderByRequestedAtDesc(
            UUID cooperativeId, UUID memberUserId, Pageable pageable);

    Page<SharePurchase> findByCooperativeIdAndMemberUserIdAndStatusOrderByRequestedAtDesc(
            UUID cooperativeId, UUID memberUserId, SharePurchaseStatus status, Pageable pageable);

    Page<SharePurchase> findByCooperativeIdOrderByRequestedAtDesc(UUID cooperativeId, Pageable pageable);

    boolean existsByCooperativeIdAndMemberUserIdAndStatus(
            UUID cooperativeId, UUID memberUserId, SharePurchaseStatus status);

    long countByCooperativeIdAndStatus(UUID cooperativeId, SharePurchaseStatus status);

    long countByCooperativeIdInAndStatus(Collection<UUID> cooperativeIds, SharePurchaseStatus status);

    long countByStatus(SharePurchaseStatus status);

    List<SharePurchase> findByCooperativeIdAndStatusAndReviewedAtBetween(
            UUID cooperativeId, SharePurchaseStatus status, Instant from, Instant to);

    @Query(
            """
            SELECT COALESCE(SUM(p.numberOfShares), 0)
            FROM SharePurchase p
            WHERE p.cooperativeId = :cooperativeId
              AND p.memberUserId = :memberUserId
              AND p.status = rw.terimbere.csams.modules.share.entity.SharePurchaseStatus.APPROVED
              AND p.reviewedAt >= :from
              AND p.reviewedAt <= :to
            """)
    Number sumApprovedSharesInPeriod(
            @Param("cooperativeId") UUID cooperativeId,
            @Param("memberUserId") UUID memberUserId,
            @Param("from") Instant from,
            @Param("to") Instant to);

    @Query(
            """
            SELECT COALESCE(SUM(p.numberOfShares), 0)
            FROM SharePurchase p
            WHERE p.cooperativeId = :cooperativeId
              AND p.memberUserId = :memberUserId
              AND p.status = rw.terimbere.csams.modules.share.entity.SharePurchaseStatus.APPROVED
              AND p.reviewedAt > :after
            """)
    Number sumApprovedSharesAfter(
            @Param("cooperativeId") UUID cooperativeId,
            @Param("memberUserId") UUID memberUserId,
            @Param("after") Instant after);

    @Query(
            """
            SELECT COALESCE(SUM(p.numberOfShares), 0)
            FROM SharePurchase p
            WHERE p.cooperativeId = :cooperativeId
              AND p.status = rw.terimbere.csams.modules.share.entity.SharePurchaseStatus.APPROVED
              AND p.reviewedAt >= :from
              AND p.reviewedAt <= :to
            """)
    Number sumApprovedSharesInPeriodForCooperative(
            @Param("cooperativeId") UUID cooperativeId, @Param("from") Instant from, @Param("to") Instant to);

    @Query(
            """
            SELECT COALESCE(SUM(p.totalAmount), 0)
            FROM SharePurchase p
            WHERE p.cooperativeId = :cooperativeId
              AND p.status = rw.terimbere.csams.modules.share.entity.SharePurchaseStatus.APPROVED
              AND p.reviewedAt >= :from
              AND p.reviewedAt <= :to
            """)
    java.math.BigDecimal sumApprovedAmountInPeriod(
            @Param("cooperativeId") UUID cooperativeId, @Param("from") Instant from, @Param("to") Instant to);

    @Query(
            """
            SELECT COALESCE(SUM(p.totalAmount), 0)
            FROM SharePurchase p
            WHERE p.cooperativeId = :cooperativeId
              AND p.memberUserId = :memberUserId
              AND p.status = rw.terimbere.csams.modules.share.entity.SharePurchaseStatus.APPROVED
            """)
    java.math.BigDecimal sumApprovedAmountByMember(
            @Param("cooperativeId") UUID cooperativeId, @Param("memberUserId") UUID memberUserId);

    @Query(
            """
            SELECT COALESCE(SUM(p.numberOfShares), 0)
            FROM SharePurchase p
            WHERE p.cooperativeId = :cooperativeId
              AND p.memberUserId = :memberUserId
              AND p.status = rw.terimbere.csams.modules.share.entity.SharePurchaseStatus.APPROVED
              AND p.reviewedAt <= :to
            """)
    Number sumApprovedSharesUpTo(
            @Param("cooperativeId") UUID cooperativeId,
            @Param("memberUserId") UUID memberUserId,
            @Param("to") Instant to);
}
