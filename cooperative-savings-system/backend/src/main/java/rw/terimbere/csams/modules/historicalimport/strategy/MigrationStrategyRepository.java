package rw.terimbere.csams.modules.historicalimport.strategy;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MigrationStrategyRepository extends JpaRepository<MigrationStrategyRecord, UUID> {

    Optional<MigrationStrategyRecord> findByCooperativeId(UUID cooperativeId);

    java.util.List<MigrationStrategyRecord> findByNeedsReviewTrueOrderByCreatedAtAsc();

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from MigrationStrategyRecord r where r.cooperativeId = :cooperativeId")
    Optional<MigrationStrategyRecord> findByCooperativeIdForUpdate(@Param("cooperativeId") UUID cooperativeId);
}
