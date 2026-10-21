package rw.terimbere.csams.modules.investment.entity;

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
import rw.terimbere.csams.shared.common.entity.BaseEntity;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "investments")
public class Investment extends BaseEntity {

    @Column(name = "cooperative_id", nullable = false)
    private UUID cooperativeId;

    @Column(name = "name", nullable = false, length = 255)
    private String name;

    @Column(name = "description", length = 2000)
    private String description;

    @Column(name = "amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal amount;

    @Column(name = "expected_return_amount", precision = 19, scale = 4)
    private BigDecimal expectedReturnAmount;

    @Column(name = "expected_return_date")
    private LocalDate expectedReturnDate;

    @Builder.Default
    @Column(name = "remaining_capital", nullable = false, precision = 19, scale = 4)
    private BigDecimal remainingCapital = BigDecimal.ZERO;

    @Builder.Default
    @Column(name = "total_capital_returned", nullable = false, precision = 19, scale = 4)
    private BigDecimal totalCapitalReturned = BigDecimal.ZERO;

    @Builder.Default
    @Column(name = "total_profit_returned", nullable = false, precision = 19, scale = 4)
    private BigDecimal totalProfitReturned = BigDecimal.ZERO;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private InvestmentStatus status;

    @Column(name = "document_file_key", length = 512)
    private String documentFileKey;

    @Column(name = "activated_at")
    private Instant activatedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "created_by")
    private UUID createdBy;
}
