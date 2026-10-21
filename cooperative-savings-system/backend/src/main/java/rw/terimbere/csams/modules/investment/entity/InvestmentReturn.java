package rw.terimbere.csams.modules.investment.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/**
 * Immutable investment return record. Prefer insert-only; no business updates.
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "investment_returns")
@EntityListeners(AuditingEntityListener.class)
public class InvestmentReturn {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(nullable = false, updatable = false)
    private UUID id;

    @Column(name = "investment_id", nullable = false, updatable = false)
    private UUID investmentId;

    @Column(name = "cooperative_id", nullable = false, updatable = false)
    private UUID cooperativeId;

    @Column(name = "return_date", nullable = false, updatable = false)
    private LocalDate returnDate;

    @Builder.Default
    @Column(name = "capital_portion", nullable = false, precision = 19, scale = 4, updatable = false)
    private BigDecimal capitalPortion = BigDecimal.ZERO;

    @Builder.Default
    @Column(name = "profit_portion", nullable = false, precision = 19, scale = 4, updatable = false)
    private BigDecimal profitPortion = BigDecimal.ZERO;

    @Column(name = "amount_total", nullable = false, precision = 19, scale = 4, updatable = false)
    private BigDecimal amountTotal;

    @Column(name = "notes", length = 2000, updatable = false)
    private String notes;

    @Column(name = "reference", length = 128, updatable = false)
    private String reference;

    @Column(name = "recorded_by", updatable = false)
    private UUID recordedBy;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Version
    @Column(nullable = false)
    private Long version;
}
