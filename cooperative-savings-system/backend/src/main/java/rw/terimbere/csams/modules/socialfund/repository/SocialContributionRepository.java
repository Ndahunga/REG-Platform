package rw.terimbere.csams.modules.socialfund.repository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import rw.terimbere.csams.modules.socialfund.entity.SocialContribution;
import rw.terimbere.csams.modules.socialfund.entity.SocialContributionStatus;

public interface SocialContributionRepository extends JpaRepository<SocialContribution, UUID> {

    Optional<SocialContribution> findByIdAndCooperativeId(UUID id, UUID cooperativeId);

    Page<SocialContribution> findByCooperativeId(UUID cooperativeId, Pageable pageable);

    Page<SocialContribution> findByCooperativeIdAndStatus(
            UUID cooperativeId, SocialContributionStatus status, Pageable pageable);

    Page<SocialContribution> findByCooperativeIdAndMemberUserId(
            UUID cooperativeId, UUID memberUserId, Pageable pageable);

    Page<SocialContribution> findByCooperativeIdAndMemberUserIdAndStatus(
            UUID cooperativeId, UUID memberUserId, SocialContributionStatus status, Pageable pageable);

    List<SocialContribution> findByCooperativeIdAndMemberUserIdAndContributionDateAndAmount(
            UUID cooperativeId, UUID memberUserId, LocalDate contributionDate, BigDecimal amount);

    List<SocialContribution> findByCooperativeIdAndMemberUserIdOrderByContributionDateDescCreatedAtDesc(
            UUID cooperativeId, UUID memberUserId);

    List<SocialContribution> findTop20ByCooperativeIdAndMemberUserIdOrderByContributionDateDescCreatedAtDesc(
            UUID cooperativeId, UUID memberUserId);

    long countByCooperativeIdAndStatus(UUID cooperativeId, SocialContributionStatus status);

    long countByStatus(SocialContributionStatus status);

    @Query(
            """
            SELECT COALESCE(SUM(c.amount), 0)
            FROM SocialContribution c
            WHERE c.cooperativeId = :cooperativeId
              AND c.status = rw.terimbere.csams.modules.socialfund.entity.SocialContributionStatus.APPROVED
            """)
    BigDecimal sumApprovedAmount(@Param("cooperativeId") UUID cooperativeId);

    @Query(
            """
            SELECT COALESCE(SUM(c.amount), 0)
            FROM SocialContribution c
            WHERE c.cooperativeId = :cooperativeId
              AND c.memberUserId = :memberUserId
              AND c.status = rw.terimbere.csams.modules.socialfund.entity.SocialContributionStatus.APPROVED
            """)
    BigDecimal sumApprovedAmountByMember(
            @Param("cooperativeId") UUID cooperativeId, @Param("memberUserId") UUID memberUserId);

    @Query(
            """
            SELECT c FROM SocialContribution c
            WHERE c.cooperativeId = :cooperativeId
              AND c.status = rw.terimbere.csams.modules.socialfund.entity.SocialContributionStatus.APPROVED
              AND c.contributionDate >= :fromDate
              AND c.contributionDate <= :toDate
            ORDER BY c.contributionDate ASC, c.createdAt ASC
            """)
    List<SocialContribution> findApprovedInPeriod(
            @Param("cooperativeId") UUID cooperativeId,
            @Param("fromDate") LocalDate fromDate,
            @Param("toDate") LocalDate toDate);
}
