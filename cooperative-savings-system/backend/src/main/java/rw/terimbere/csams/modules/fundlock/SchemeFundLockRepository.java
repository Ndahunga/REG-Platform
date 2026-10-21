package rw.terimbere.csams.modules.fundlock;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/** Deliberately minimal: no generic save/delete, only the two operations the lock needs. */
public interface SchemeFundLockRepository extends Repository<SchemeFundLock, UUID> {

    /**
     * Locks the scheme's row ({@code SELECT ... FOR UPDATE}) inside the caller's transaction. The lock is held until
     * that transaction commits or rolls back. Only that scheme's row is touched, so other schemes are never blocked.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT l FROM SchemeFundLock l WHERE l.cooperativeId = :cooperativeId")
    Optional<SchemeFundLock> findForUpdate(@Param("cooperativeId") UUID cooperativeId);

    /**
     * Single-statement insert for a brand-new scheme. A duplicate violates the primary key and fails loudly. Flushes
     * first so the scheme row it references already exists in the same transaction.
     */
    @Modifying(flushAutomatically = true)
    @Query(
            value = "INSERT INTO scheme_fund_locks (cooperative_id) VALUES (:cooperativeId)",
            nativeQuery = true)
    int insertForNewScheme(@Param("cooperativeId") UUID cooperativeId);
}
