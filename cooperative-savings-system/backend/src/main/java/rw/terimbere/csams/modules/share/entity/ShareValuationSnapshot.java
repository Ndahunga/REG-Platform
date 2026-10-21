package rw.terimbere.csams.modules.share.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
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
@Table(name = "share_valuation_snapshots")
public class ShareValuationSnapshot extends BaseEntity {

    @Column(name = "cooperative_id", nullable = false)
    private UUID cooperativeId;

    @Column(name = "calculated_at", nullable = false)
    private Instant calculatedAt;

    @Column(name = "available_funds", nullable = false, precision = 19, scale = 4)
    private BigDecimal availableFunds;

    @Column(name = "outstanding_loans", nullable = false, precision = 19, scale = 4)
    private BigDecimal outstandingLoans;

    @Column(name = "unpaid_interest", nullable = false, precision = 19, scale = 4)
    private BigDecimal unpaidInterest;

    @Column(name = "unpaid_penalties", nullable = false, precision = 19, scale = 4)
    private BigDecimal unpaidPenalties;

    @Column(name = "other_assets", nullable = false, precision = 19, scale = 4)
    private BigDecimal otherAssets;

    @Column(name = "liabilities", nullable = false, precision = 19, scale = 4)
    private BigDecimal liabilities;

    @Column(name = "total_ikimina_value", nullable = false, precision = 19, scale = 4)
    private BigDecimal totalIkiminaValue;

    @Column(name = "total_existing_shares", nullable = false)
    private long totalExistingShares;

    @Column(name = "current_share_value", nullable = false, precision = 19, scale = 4)
    private BigDecimal currentShareValue;

    @Enumerated(EnumType.STRING)
    @Column(name = "reason", nullable = false, length = 32)
    private ShareValuationSnapshotReason reason;

    @Column(name = "source_entity_type", length = 64)
    private String sourceEntityType;

    @Column(name = "source_entity_id")
    private UUID sourceEntityId;
}
