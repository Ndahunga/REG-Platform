package rw.terimbere.csams.modules.settings.repository;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import rw.terimbere.csams.modules.settings.entity.CooperativeSettings;

public interface CooperativeSettingsRepository extends JpaRepository<CooperativeSettings, UUID> {

    Optional<CooperativeSettings> findByCooperativeId(UUID cooperativeId);
}
