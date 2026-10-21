package rw.terimbere.csams.modules.loan.repository;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import rw.terimbere.csams.modules.loan.entity.LoanSettings;

public interface LoanSettingsRepository extends JpaRepository<LoanSettings, UUID> {

    Optional<LoanSettings> findByCooperativeId(UUID cooperativeId);

    boolean existsByCooperativeId(UUID cooperativeId);
}
