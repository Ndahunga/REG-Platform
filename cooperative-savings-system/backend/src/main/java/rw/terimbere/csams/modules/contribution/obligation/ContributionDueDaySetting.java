package rw.terimbere.csams.modules.contribution.obligation;

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
 * The monthly contribution due day in force from {@code effectiveYear}/{@code effectiveMonth} onward. Insert-only:
 * every column is non-updatable and there is no setter, so history is never rewritten. The setting governing a period is
 * the latest one effective on or before it; a scheme with no row has no configured due day.
 */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "contribution_due_day_settings")
public class ContributionDueDaySetting {

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

    @Column(name = "due_day", nullable = false, updatable = false)
    private int dueDay;

    @Column(name = "created_by", nullable = false, updatable = false)
    private UUID createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}
