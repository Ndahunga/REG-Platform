package rw.terimbere.csams.modules.historicalimport.transactions;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface HistoricalLedgerRowRepository extends JpaRepository<HistoricalLedgerRow, UUID> {

    List<HistoricalLedgerRow> findByCooperativeIdAndImportIdOrderBySheetAscRowNumberAsc(UUID cooperativeId, UUID importId);

    @Query("select r.postedKey from HistoricalLedgerRow r where r.cooperativeId = :coop and r.postedKey in :keys")
    List<String> findPostedKeys(@Param("coop") UUID cooperativeId, @Param("keys") Collection<String> keys);
}
