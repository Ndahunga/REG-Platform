package rw.terimbere.csams.modules.rotation.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import rw.terimbere.csams.shared.common.entity.BaseEntity;

/**
 * One member's turn in a month of a share-based cycle: {@code shareCount} shares, each worth the cycle's payout per
 * share. A month can hold several turns. {@code openingReceivedAmount} is what the member already received for this turn
 * before the scheme moved to the platform (migration); it is history, never a ledger entry.
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "rotation_turns")
public class RotationTurn extends BaseEntity {

    @Column(name = "cooperative_id", nullable = false, updatable = false)
    private UUID cooperativeId;

    @Column(name = "cycle_id", nullable = false, updatable = false)
    private UUID cycleId;

    @Column(name = "rotation_period_id", nullable = false)
    private UUID rotationPeriodId;

    @Column(name = "member_user_id", nullable = false)
    private UUID memberUserId;

    @Column(name = "share_count", nullable = false)
    private int shareCount;

    @Column(name = "payout_amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal payoutAmount;

    @Builder.Default
    @Column(name = "opening_received_amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal openingReceivedAmount = BigDecimal.ZERO;

    @Column(name = "opening_received_date")
    private LocalDate openingReceivedDate;
}
