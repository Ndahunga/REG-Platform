package rw.terimbere.csams.modules.rotation.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * An effective-dated rotating/retained split, in basis points (10000 = 100%). Insert-only: every column is
 * non-updatable and there is no setter, so a stored configuration can never be altered. The configuration governing a
 * period is the latest one whose effective month is on or before it.
 */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "rotation_configurations")
public class RotationConfiguration {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(nullable = false, updatable = false)
    private UUID id;

    @Column(name = "cooperative_id", nullable = false, updatable = false)
    private UUID cooperativeId;

    @Column(name = "effective_year", nullable = false, updatable = false)
    private int effectiveYear;

    @Column(name = "effective_month", nullable = false, updatable = false)
    private int effectiveMonth;

    @Column(name = "rotating_bps", nullable = false, updatable = false)
    private int rotatingBps;

    @Column(name = "retained_bps", nullable = false, updatable = false)
    private int retainedBps;

    @Column(name = "created_by", nullable = false, updatable = false)
    private UUID createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}
