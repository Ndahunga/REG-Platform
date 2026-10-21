package rw.terimbere.csams.modules.contribution.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import rw.terimbere.csams.modules.contribution.entity.ContributionImport;

public interface ContributionImportRepository extends JpaRepository<ContributionImport, UUID> {

    Optional<ContributionImport> findByIdAndCooperativeId(UUID id, UUID cooperativeId);

    List<ContributionImport> findByCooperativeIdOrderByCreatedAtDesc(UUID cooperativeId);
}
