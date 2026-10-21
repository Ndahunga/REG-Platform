package rw.terimbere.csams.modules.contribution.repository;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import rw.terimbere.csams.modules.contribution.entity.ContributionImportRow;

public interface ContributionImportRowRepository extends JpaRepository<ContributionImportRow, UUID> {

    List<ContributionImportRow> findByImportIdOrderByRowNumberAsc(UUID importId);

    List<ContributionImportRow> findByImportIdAndValidTrueOrderByRowNumberAsc(UUID importId);

    void deleteByImportId(UUID importId);
}
