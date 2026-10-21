package rw.terimbere.csams.modules.historicalimport.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import rw.terimbere.csams.modules.historicalimport.entity.HistoricalImportRow;
import rw.terimbere.csams.modules.historicalimport.entity.HistoricalImportStatus;

public interface HistoricalImportRowRepository extends JpaRepository<HistoricalImportRow, UUID> {

    List<HistoricalImportRow> findByImportIdOrderBySheetAscRowNumberAsc(UUID importId);

    void deleteByImportId(UUID importId);

    List<HistoricalImportRow> findByFingerprintAndResultingEntityIdIsNotNull(String fingerprint);

    @Query(
            """
            select r from HistoricalImportRow r, rw.terimbere.csams.modules.historicalimport.entity.HistoricalImport i
            where r.importId = i.id
              and i.cooperativeId = :cooperativeId
              and i.status = :status
              and r.sheet = :sheet
              and lower(r.sourceKey) = lower(:sourceKey)
              and r.resultingEntityId is not null
            order by r.createdAt desc
            """)
    List<HistoricalImportRow> findConfirmedSource(
            @Param("cooperativeId") UUID cooperativeId,
            @Param("sheet") String sheet,
            @Param("sourceKey") String sourceKey,
            @Param("status") HistoricalImportStatus status);

    default Optional<HistoricalImportRow> findConfirmedSource(
            UUID cooperativeId, String sheet, String sourceKey) {
        List<HistoricalImportRow> rows =
                findConfirmedSource(cooperativeId, sheet, sourceKey, HistoricalImportStatus.CONFIRMED);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }
}
