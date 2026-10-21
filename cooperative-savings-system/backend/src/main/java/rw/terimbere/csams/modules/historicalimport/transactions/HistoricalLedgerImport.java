package rw.terimbere.csams.modules.historicalimport.transactions;

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

/** One uploaded historical workbook: validated first, posted only after an independent approval. */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "historical_ledger_imports")
public class HistoricalLedgerImport extends BaseEntity {

    @Column(name = "cooperative_id", nullable = false, updatable = false)
    private UUID cooperativeId;

    @Column(name = "file_name", nullable = false, updatable = false, length = 255)
    private String fileName;

    @Column(name = "file_sha256", nullable = false, updatable = false, length = 64)
    private String fileSha256;

    @Column(name = "active_key", length = 64)
    private String activeKey;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private HistoricalBatchStatus status;

    @Column(name = "currency", length = 3)
    private String currency;

    @Column(name = "history_end_date")
    private LocalDate historyEndDate;

    @Column(name = "opening_balance", precision = 19, scale = 4)
    private BigDecimal openingBalance;

    @Column(name = "declared_closing_balance", precision = 19, scale = 4)
    private BigDecimal declaredClosingBalance;

    @Column(name = "computed_closing_balance", precision = 19, scale = 4)
    private BigDecimal computedClosingBalance;

    @Column(name = "balance_variance", precision = 19, scale = 4)
    private BigDecimal balanceVariance;

    @Column(name = "total_rows", nullable = false)
    private int totalRows;

    @Column(name = "valid_rows", nullable = false)
    private int validRows;

    @Column(name = "error_rows", nullable = false)
    private int errorRows;

    @Column(name = "error_count", nullable = false)
    private int errorCount;

    /** JSON report: per-year and per-type totals, loan closure, warnings, unresolved attribution. */
    @Column(name = "summary", columnDefinition = "TEXT")
    private String summary;

    @Column(name = "uploaded_by", nullable = false, updatable = false)
    private UUID uploadedBy;

    @Column(name = "uploaded_at", nullable = false, updatable = false)
    private Instant uploadedAt;

    @Column(name = "approved_by")
    private UUID approvedBy;

    @Column(name = "approved_at")
    private Instant approvedAt;

    @Column(name = "variance_acknowledged", nullable = false)
    private boolean varianceAcknowledged;

    @Column(name = "variance_note", length = 1000)
    private String varianceNote;

    @Column(name = "cancelled_by")
    private UUID cancelledBy;

    @Column(name = "cancelled_at")
    private Instant cancelledAt;
}
