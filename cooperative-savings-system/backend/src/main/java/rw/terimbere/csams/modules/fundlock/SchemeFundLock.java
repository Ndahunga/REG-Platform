package rw.terimbere.csams.modules.fundlock;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * One row per Saving Scheme, used only as a lock target (see {@link SchemeFundLockService}). It deliberately does not
 * extend {@code BaseEntity}: there is no version to bump, because the row is never updated, only locked.
 */
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "scheme_fund_locks")
public class SchemeFundLock {

    @Id
    @Column(name = "cooperative_id", nullable = false, updatable = false)
    private UUID cooperativeId;

    @Column(name = "created_at", insertable = false, updatable = false)
    private Instant createdAt;
}
