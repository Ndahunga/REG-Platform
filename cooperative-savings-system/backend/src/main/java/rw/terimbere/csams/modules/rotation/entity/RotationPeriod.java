package rw.terimbere.csams.modules.rotation.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import rw.terimbere.csams.shared.common.entity.BaseEntity;

/**
 * One monthly slot of a cycle and its scheduled recipient. The row's identity (id, cycle, month, sequence) never
 * changes; only the recipient may be reassigned before the period begins, with a {@link RotationRecipientChange}
 * recorded each time. {@code slotKey} (year-month text) is set only while the period holds its month in a live cycle.
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "rotation_periods")
public class RotationPeriod extends BaseEntity {

    @Column(name = "cooperative_id", nullable = false, updatable = false)
    private UUID cooperativeId;

    @Column(name = "cycle_id", nullable = false, updatable = false)
    private UUID cycleId;

    @Column(name = "sequence_number", nullable = false, updatable = false)
    private int sequenceNumber;

    @Column(name = "period_year", nullable = false, updatable = false)
    private int periodYear;

    @Column(name = "period_month", nullable = false, updatable = false)
    private int periodMonth;

    @Column(name = "scheduled_recipient_user_id", nullable = false)
    private UUID scheduledRecipientUserId;

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private RotationPeriodStatus status = RotationPeriodStatus.SCHEDULED;

    @Column(name = "slot_key", length = 7)
    private String slotKey;
}
