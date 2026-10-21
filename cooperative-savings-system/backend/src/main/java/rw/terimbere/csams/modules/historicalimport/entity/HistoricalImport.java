package rw.terimbere.csams.modules.historicalimport.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
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
@Table(name = "historical_imports")
public class HistoricalImport extends BaseEntity {

    @Column(name = "cooperative_id", nullable = false)
    private UUID cooperativeId;

    @Column(name = "original_filename", length = 512)
    private String originalFilename;

    @Column(name = "storage_key", length = 1024)
    private String storageKey;

    @Column(name = "content_type", length = 128)
    private String contentType;

    @Column(name = "size_bytes")
    private Long sizeBytes;

    @Column(name = "file_hash", nullable = false, length = 64)
    private String fileHash;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private HistoricalImportStatus status;

    @Builder.Default
    @Column(name = "total_rows", nullable = false)
    private int totalRows = 0;

    @Builder.Default
    @Column(name = "valid_rows", nullable = false)
    private int validRows = 0;

    @Builder.Default
    @Column(name = "invalid_rows", nullable = false)
    private int invalidRows = 0;

    @Column(name = "sheet_summary", columnDefinition = "TEXT")
    private String sheetSummary;

    @Column(name = "reconciliation_summary", columnDefinition = "TEXT")
    private String reconciliationSummary;

    /**
     * "Historical data through" date of an opening-balance migration: every imported balance is the
     * Saving Scheme's position at the end of this day. Null for legacy 16-sheet imports.
     */
    @Column(name = "cutover_date")
    private LocalDate cutoverDate;

    /**
     * Actual funds available to the Saving Scheme at the end of the cutover date (already net of anything
     * spent or lent before it). Starting point of Available Group Funds. Null for legacy imports.
     */
    @Column(name = "opening_available_funds", precision = 19, scale = 4)
    private java.math.BigDecimal openingAvailableFunds;

    /** Whether this import carried members and share balances or the loans outstanding at the cutover. */
    @jakarta.persistence.Enumerated(jakarta.persistence.EnumType.STRING)
    @Column(name = "import_kind", nullable = false, length = 20)
    @lombok.Builder.Default
    private HistoricalImportKind importKind = HistoricalImportKind.OPENING_BALANCE;

    /** JSON {@code HistoricalMigrationSummary} for opening-balance migrations; null for legacy imports. */
    @Column(name = "migration_summary", columnDefinition = "TEXT")
    private String migrationSummary;

    @Column(name = "error_summary", columnDefinition = "TEXT")
    private String errorSummary;

    @Column(name = "uploaded_by")
    private UUID uploadedBy;

    @Column(name = "validated_at")
    private Instant validatedAt;

    @Column(name = "confirmed_by")
    private UUID confirmedBy;

    @Column(name = "confirmed_at")
    private Instant confirmedAt;

    @Column(name = "cancelled_by")
    private UUID cancelledBy;

    @Column(name = "cancelled_at")
    private Instant cancelledAt;
}
