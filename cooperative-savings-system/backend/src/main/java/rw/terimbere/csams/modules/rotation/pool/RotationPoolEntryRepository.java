package rw.terimbere.csams.modules.rotation.pool;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RotationPoolEntryRepository extends JpaRepository<RotationPoolEntry, UUID> {

    List<RotationPoolEntry> findByCooperativeIdOrderByCreatedAtAscIdAsc(UUID cooperativeId);

    Optional<RotationPoolEntry> findByCooperativeIdAndIdempotencyKey(UUID cooperativeId, String idempotencyKey);

    boolean existsByCooperativeIdAndEntryType(UUID cooperativeId, PoolEntryType entryType);

    @Query("select coalesce(sum(e.amount), 0) from RotationPoolEntry e where e.cooperativeId = :cooperativeId and e.entryType = :type")
    BigDecimal sumByType(@Param("cooperativeId") UUID cooperativeId, @Param("type") PoolEntryType type);
}
