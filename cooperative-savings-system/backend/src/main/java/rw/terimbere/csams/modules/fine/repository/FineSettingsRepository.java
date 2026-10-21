package rw.terimbere.csams.modules.fine.repository;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import rw.terimbere.csams.modules.fine.entity.FineSettings;

public interface FineSettingsRepository extends JpaRepository<FineSettings, UUID> {

    Optional<FineSettings> findByCooperativeId(UUID cooperativeId);

    boolean existsByCooperativeId(UUID cooperativeId);
}
