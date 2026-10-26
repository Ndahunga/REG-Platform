package rw.terimbere.csams.modules.fine.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.math.BigDecimal;
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
@Table(name = "fine_settings")
public class FineSettings extends BaseEntity {

    @Column(name = "cooperative_id", nullable = false, unique = true)
    private UUID cooperativeId;

    @Builder.Default
    @Column(name = "auto_fines_enabled", nullable = false)
    private boolean autoFinesEnabled = true;

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(name = "fine_mode", nullable = false, length = 32)
    private FineCalculationMode fineMode = FineCalculationMode.FIXED;

    @Builder.Default
    @Column(name = "base_fine_amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal baseFineAmount = BigDecimal.ZERO;

    @Builder.Default
    @Column(name = "daily_increment", nullable = false, precision = 19, scale = 4)
    private BigDecimal dailyIncrement = BigDecimal.ZERO;

    @Builder.Default
    @Column(name = "grace_days", nullable = false)
    private int graceDays = 0;

    @Builder.Default
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "currency", nullable = false, length = 3)
    private String currency = "RWF";

    /** For BANDED mode: "1-5:2000;6-15:5000;16-30:10000" (days late after grace : amount). */
    @Column(name = "penalty_bands", length = 500)
    private String penaltyBands;

    /** Multiply the penalty by the shares of the late contribution. */
    @Builder.Default
    @Column(name = "per_share", nullable = false)
    private boolean perShare = false;

    /** After this many days late, an unpaid contribution is charged {@link #creditConversionRatePercent} a month. */
    @Column(name = "credit_conversion_days")
    private Integer creditConversionDays;

    @Column(name = "credit_conversion_rate_percent", precision = 8, scale = 4)
    private BigDecimal creditConversionRatePercent;

    /** Months over which a converted contribution is repaid as a loan. */
    @Column(name = "credit_conversion_term_months")
    private Integer creditConversionTermMonths;
}
