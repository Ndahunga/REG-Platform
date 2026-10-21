package rw.terimbere.csams.modules.historicalimport.transactions;

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

/** One workbook row as read, with its validation result. Rows are never edited or removed once posted. */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "historical_ledger_rows")
public class HistoricalLedgerRow extends BaseEntity {

    @Column(name = "import_id", nullable = false, updatable = false)
    private UUID importId;

    @Column(name = "cooperative_id", nullable = false, updatable = false)
    private UUID cooperativeId;

    /** MEMBERS or TRANSACTIONS. */
    @Column(name = "sheet", nullable = false, updatable = false, length = 16)
    private String sheet;

    @Column(name = "row_number", nullable = false, updatable = false)
    private int rowNumber;

    @Column(name = "row_type", nullable = false, updatable = false, length = 40)
    private String rowType;

    @Column(name = "username", updatable = false, length = 100)
    private String username;

    @Column(name = "member_user_id", updatable = false)
    private UUID memberUserId;

    @Column(name = "event_date", updatable = false)
    private LocalDate eventDate;

    @Column(name = "amount", updatable = false, precision = 19, scale = 4)
    private BigDecimal amount;

    @Column(name = "original_reference", updatable = false, length = 128)
    private String originalReference;

    @Column(name = "period_year", updatable = false)
    private Integer periodYear;

    @Column(name = "period_month", updatable = false)
    private Integer periodMonth;

    @Column(name = "loan_reference", updatable = false, length = 64)
    private String loanReference;

    @Column(name = "operating_model", updatable = false, length = 16)
    private String operatingModel;

    @Column(name = "description", updatable = false, length = 500)
    private String description;

    @Column(name = "setting_key", updatable = false, length = 40)
    private String settingKey;

    @Column(name = "setting_value", updatable = false, length = 100)
    private String settingValue;

    @Column(name = "fingerprint", updatable = false, length = 64)
    private String fingerprint;

    @Column(name = "valid", nullable = false)
    private boolean valid;

    /** JSON array of {code, field, message}. */
    @Column(name = "errors", columnDefinition = "TEXT")
    private String errors;

    @Column(name = "posted_key", length = 64)
    private String postedKey;

    @Column(name = "ledger_entry_id")
    private UUID ledgerEntryId;

    @Column(name = "contribution_id")
    private UUID contributionId;

    @Column(name = "operational_type", length = 32)
    private String operationalType;

    @Column(name = "operational_id")
    private UUID operationalId;
}
