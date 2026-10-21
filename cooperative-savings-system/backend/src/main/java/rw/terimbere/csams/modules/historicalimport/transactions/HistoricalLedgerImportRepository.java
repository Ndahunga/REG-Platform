package rw.terimbere.csams.modules.historicalimport.transactions;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface HistoricalLedgerImportRepository extends JpaRepository<HistoricalLedgerImport, UUID> {

    Optional<HistoricalLedgerImport> findByIdAndCooperativeId(UUID id, UUID cooperativeId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select i from HistoricalLedgerImport i where i.id = :id and i.cooperativeId = :cooperativeId")
    Optional<HistoricalLedgerImport> findByIdForUpdate(@Param("id") UUID id, @Param("cooperativeId") UUID cooperativeId);

    Optional<HistoricalLedgerImport> findByCooperativeIdAndActiveKey(UUID cooperativeId, String activeKey);

    boolean existsByCooperativeIdAndStatus(UUID cooperativeId, HistoricalBatchStatus status);

    List<HistoricalLedgerImport> findByCooperativeIdOrderByUploadedAtDesc(UUID cooperativeId);
}
