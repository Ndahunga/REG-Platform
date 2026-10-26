package rw.terimbere.csams.modules.rotation.payout;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.Immutable;
import rw.terimbere.csams.shared.common.entity.BaseEntity;

/** Part of a disbursed turn payout applied to one of the recipient's overdue contributions instead of being paid out. */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Immutable
@Entity
@Table(name = "rotation_payout_offsets")
public class RotationPayoutOffset extends BaseEntity {

    @Column(name = "cooperative_id", nullable = false, updatable = false)
    private UUID cooperativeId;

    @Column(name = "payout_id", nullable = false, updatable = false)
    private UUID payoutId;

    @Column(name = "contribution_id", nullable = false, updatable = false)
    private UUID contributionId;

    @Column(name = "rotation_period_id", updatable = false)
    private UUID rotationPeriodId;

    @Column(name = "amount", nullable = false, updatable = false, precision = 19, scale = 4)
    private BigDecimal amount;
}
