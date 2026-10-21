package rw.terimbere.csams.modules.specialcontribution.repository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import rw.terimbere.csams.modules.specialcontribution.entity.SpecialContribution;
import rw.terimbere.csams.modules.specialcontribution.entity.SpecialContributionStatus;

public interface SpecialContributionRepository
        extends JpaRepository<SpecialContribution, UUID>, JpaSpecificationExecutor<SpecialContribution> {

    List<SpecialContribution> findByCampaignIdOrderByCreatedAtDesc(UUID campaignId);

    List<SpecialContribution> findByCooperativeIdAndCampaignId(UUID cooperativeId, UUID campaignId);

    Optional<SpecialContribution> findByIdAndCooperativeId(UUID id, UUID cooperativeId);

    long countByCooperativeIdAndStatus(UUID cooperativeId, SpecialContributionStatus status);

    long countByStatus(SpecialContributionStatus status);

    @Query(
            """
            SELECT COALESCE(SUM(s.amount), 0)
            FROM SpecialContribution s
            WHERE s.cooperativeId = :cooperativeId
              AND s.status = rw.terimbere.csams.modules.specialcontribution.entity.SpecialContributionStatus.APPROVED
            """)
    BigDecimal sumApprovedAmount(@Param("cooperativeId") UUID cooperativeId);

    @Query(
            """
            SELECT COALESCE(SUM(s.amount), 0)
            FROM SpecialContribution s
            WHERE s.cooperativeId = :cooperativeId
              AND s.memberUserId = :memberUserId
              AND s.status = rw.terimbere.csams.modules.specialcontribution.entity.SpecialContributionStatus.APPROVED
            """)
    BigDecimal sumApprovedAmountByMember(
            @Param("cooperativeId") UUID cooperativeId, @Param("memberUserId") UUID memberUserId);

    List<SpecialContribution> findByCooperativeIdAndMemberUserIdAndCampaignIdAndContributionDateAndAmount(
            UUID cooperativeId,
            UUID memberUserId,
            UUID campaignId,
            LocalDate contributionDate,
            BigDecimal amount);

    List<SpecialContribution> findByCooperativeIdAndStatus(UUID cooperativeId, SpecialContributionStatus status);

    @Query(
            """
            SELECT s.memberUserId, COALESCE(SUM(s.amount), 0)
            FROM SpecialContribution s
            WHERE s.cooperativeId = :cooperativeId
              AND s.status = rw.terimbere.csams.modules.specialcontribution.entity.SpecialContributionStatus.APPROVED
              AND s.contributionDate >= :fromDate
              AND s.contributionDate <= :toDate
            GROUP BY s.memberUserId
            """)
    List<Object[]> sumApprovedGroupedByMemberInDateRange(
            @Param("cooperativeId") UUID cooperativeId,
            @Param("fromDate") LocalDate fromDate,
            @Param("toDate") LocalDate toDate);

    default List<SpecialContribution> findFiltered(
            UUID cooperativeId,
            UUID memberUserId,
            SpecialContributionStatus status,
            LocalDate fromDate,
            LocalDate toDate) {
        return findAll(
                SpecialContributionSpecs.filtered(cooperativeId, memberUserId, status, fromDate, toDate),
                Sort.by(Sort.Direction.DESC, "contributionDate").and(Sort.by(Sort.Direction.DESC, "createdAt")));
    }
}
