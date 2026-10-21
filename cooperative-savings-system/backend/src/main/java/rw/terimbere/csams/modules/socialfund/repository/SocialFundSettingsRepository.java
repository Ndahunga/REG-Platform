package rw.terimbere.csams.modules.socialfund.repository;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import rw.terimbere.csams.modules.socialfund.entity.SocialFundSettings;

public interface SocialFundSettingsRepository extends JpaRepository<SocialFundSettings, UUID> {

    Optional<SocialFundSettings> findByCooperativeId(UUID cooperativeId);
}
