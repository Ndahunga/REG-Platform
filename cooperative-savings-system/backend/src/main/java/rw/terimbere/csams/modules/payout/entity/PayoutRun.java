package rw.terimbere.csams.modules.payout.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import rw.terimbere.csams.shared.common.entity.BaseEntity;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "payout_runs")
public class PayoutRun extends BaseEntity {

    @Column(name = "cooperative_id", nullable = false)
    private UUID cooperativeId;

    @Column(name = "name", length = 255)
    private String name;

    @Column(name = "period_from", nullable = false)
    private LocalDate periodFrom;

    @Column(name = "period_to", nullable = false)
    private LocalDate periodTo;

    @Column(name = "include_regular", nullable = false)
    private boolean includeRegular;

    @Column(name = "include_special", nullable = false)
    private boolean includeSpecial;

    @Column(name = "available_fund_snapshot", nullable = false, precision = 19, scale = 4)
    private BigDecimal availableFundSnapshot;

    @Column(name = "payout_pool_amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal payoutPoolAmount;

    @Column(name = "total_eligible_contributions", nullable = false, precision = 19, scale = 4)
    private BigDecimal totalEligibleContributions;

    @Builder.Default
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "currency", nullable = false, length = 3)
    private String currency = "RWF";

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private PayoutRunStatus status;

    @Column(name = "confirmed_at")
    private Instant confirmedAt;

    @Column(name = "confirmed_by")
    private UUID confirmedBy;

    @Column(name = "paid_at")
    private Instant paidAt;

    @Column(name = "paid_by")
    private UUID paidBy;

    @Column(name = "created_by")
    private UUID createdBy;

    @Column(name = "notes", length = 2000)
    private String notes;
}
