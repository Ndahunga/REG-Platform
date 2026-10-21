package rw.terimbere.csams.modules.historicalimport.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "historical_import_rows")
@EntityListeners(AuditingEntityListener.class)
public class HistoricalImportRow {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(nullable = false, updatable = false)
    private UUID id;

    @Column(name = "import_id", nullable = false)
    private UUID importId;

    @Column(name = "sheet", nullable = false, length = 64)
    private String sheet;

    @Column(name = "row_number", nullable = false)
    private int rowNumber;

    @Column(name = "source_key", length = 255)
    private String sourceKey;

    @Column(name = "fingerprint", length = 64)
    private String fingerprint;

    @Builder.Default
    @Column(name = "valid", nullable = false)
    private boolean valid = false;

    @Column(name = "error_messages", columnDefinition = "TEXT")
    private String errorMessages;

    @Column(name = "resulting_entity_type", length = 64)
    private String resultingEntityType;

    @Column(name = "resulting_entity_id")
    private UUID resultingEntityId;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}
