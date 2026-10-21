package rw.terimbere.csams.modules.contribution.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
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
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "contribution_import_rows")
@EntityListeners(AuditingEntityListener.class)
public class ContributionImportRow {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(nullable = false, updatable = false)
    private UUID id;

    @Column(name = "import_id", nullable = false)
    private UUID importId;

    @Column(name = "row_number", nullable = false)
    private int rowNumber;

    @Column(name = "username", length = 64)
    private String username;

    @Column(name = "member_name", length = 255)
    private String memberName;

    @Column(name = "amount", precision = 19, scale = 4)
    private BigDecimal amount;

    @Column(name = "payment_date")
    private LocalDate paymentDate;

    @Column(name = "reference", length = 128)
    private String reference;

    @Column(name = "notes", length = 2000)
    private String notes;

    @Builder.Default
    @Column(name = "valid", nullable = false)
    private boolean valid = false;

    @Column(name = "error_messages", columnDefinition = "TEXT")
    private String errorMessages;

    @Column(name = "member_user_id")
    private UUID memberUserId;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}
