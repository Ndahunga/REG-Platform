package rw.terimbere.csams.modules.historicalimport.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import rw.terimbere.csams.modules.historicalimport.entity.HistoricalImport;
import rw.terimbere.csams.modules.historicalimport.entity.HistoricalImportStatus;

public interface HistoricalImportRepository extends JpaRepository<HistoricalImport, UUID> {

    Optional<HistoricalImport> findByIdAndCooperativeId(UUID id, UUID cooperativeId);

    List<HistoricalImport> findByCooperativeIdOrderByCreatedAtDesc(UUID cooperativeId);

    boolean existsByCooperativeIdAndFileHashAndStatus(
            UUID cooperativeId, String fileHash, HistoricalImportStatus status);

    /** Sum of the explicit cutover cash positions of confirmed opening-balance migrations. */
    @org.springframework.data.jpa.repository.Query(
            "select coalesce(sum(i.openingAvailableFunds), 0) from HistoricalImport i"
                    + " where i.cooperativeId = :cooperativeId and i.status = :status"
                    + " and i.openingAvailableFunds is not null")
    java.math.BigDecimal sumOpeningAvailableFunds(
            @org.springframework.data.repository.query.Param("cooperativeId") UUID cooperativeId,
            @org.springframework.data.repository.query.Param("status") HistoricalImportStatus status);

    boolean existsByCooperativeIdAndStatusAndCutoverDateIsNotNull(UUID cooperativeId, HistoricalImportStatus status);

    boolean existsByCooperativeIdAndImportKindAndStatus(
            UUID cooperativeId, rw.terimbere.csams.modules.historicalimport.entity.HistoricalImportKind kind, HistoricalImportStatus status);
}
