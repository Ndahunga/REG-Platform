package rw.terimbere.csams.modules.fine.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import rw.terimbere.csams.modules.fine.entity.FinePaymentMethod;
import rw.terimbere.csams.modules.fine.entity.FinePaymentStatus;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FinePaymentResponse {

    private UUID id;
    private UUID fineId;
    private UUID cooperativeId;
    private UUID memberUserId;
    /** Display helpers for payment queue / review UI (optional). */
    private String memberName;
    private String username;
    private String fineReason;
    private BigDecimal fineTotalAmount;
    private BigDecimal fineOutstandingAmount;
    private BigDecimal amount;
    private LocalDate paymentDate;
    private String paymentReference;
    private FinePaymentMethod paymentMethod;
    private String paymentMethodDetail;
    private String notes;
    private String evidenceFileKey;
    private FinePaymentStatus status;
    private UUID submittedBy;
    private UUID reviewedBy;
    private Instant reviewedAt;
    private String reviewNotes;
    private String currency;
    private Instant createdAt;
    private Instant updatedAt;
}
