package rw.terimbere.csams.modules.contribution.obligation;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ContributionDueDaySettingRepository extends JpaRepository<ContributionDueDaySetting, UUID> {

    List<ContributionDueDaySetting> findByCooperativeIdOrderByEffectiveYearDescEffectiveMonthDesc(UUID cooperativeId);

    Optional<ContributionDueDaySetting> findByCooperativeIdAndEffectiveYearAndEffectiveMonth(
            UUID cooperativeId, int effectiveYear, int effectiveMonth);

    boolean existsByCooperativeId(UUID cooperativeId);
}
