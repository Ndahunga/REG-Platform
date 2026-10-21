package rw.terimbere.csams.modules.historicalimport.transactions;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface HistoricalLedgerEventRepository extends JpaRepository<HistoricalLedgerImportEvent, UUID> {

    List<HistoricalLedgerImportEvent> findByCooperativeIdAndImportIdOrderByCreatedAtAsc(UUID cooperativeId, UUID importId);
}
