package rw.terimbere.csams.modules.historicalimport.strategy;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.LocalDate;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import rw.terimbere.csams.shared.common.entity.BaseEntity;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "cooperative_migration_strategies")
public class MigrationStrategyRecord extends BaseEntity {

    @Column(name = "cooperative_id", nullable = false, updatable = false)
    private UUID cooperativeId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 40)
    private MigrationStrategy strategy;

    @Column(name = "cutover_date")
    private LocalDate cutoverDate;

    /** EXPLICIT (an officer chose it), FIRST_IMPORT (set by the first import) or BACKFILL (derived from older imports). */
    @Column(nullable = false, length = 20)
    private String source;

    /** True when the scheme had already imported both ways: no further import is accepted until it is reconciled. */
    @Column(name = "needs_review", nullable = false)
    private boolean needsReview;

    @Column(name = "set_by")
    private UUID setBy;

    @Column(name = "resolved_by")
    private UUID resolvedBy;

    @Column(name = "resolved_at")
    private java.time.Instant resolvedAt;

    @Column(name = "resolution_note", length = 1000)
    private String resolutionNote;
}
