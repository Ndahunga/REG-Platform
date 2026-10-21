package rw.terimbere.csams.modules.specialcontribution.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import rw.terimbere.csams.modules.specialcontribution.entity.SpecialCampaignStatus;
import rw.terimbere.csams.modules.specialcontribution.entity.SpecialContributionCampaign;

public interface SpecialContributionCampaignRepository extends JpaRepository<SpecialContributionCampaign, UUID> {

    List<SpecialContributionCampaign> findByCooperativeIdOrderByCreatedAtDesc(UUID cooperativeId);

    List<SpecialContributionCampaign> findByCooperativeIdAndStatus(UUID cooperativeId, SpecialCampaignStatus status);

    Optional<SpecialContributionCampaign> findByIdAndCooperativeId(UUID id, UUID cooperativeId);

    List<SpecialContributionCampaign> findByCooperativeIdAndNameIgnoreCase(UUID cooperativeId, String name);
}
