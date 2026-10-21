package rw.terimbere.csams.modules.share.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import rw.terimbere.csams.modules.share.entity.SharePurchaseStatus;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SharePurchaseResponse {

    private UUID id;
    private UUID cooperativeId;
    private UUID memberUserId;
    private String memberName;
    private Integer numberOfShares;
    private BigDecimal pricePerShare;
    private BigDecimal currentShareValue;
    private BigDecimal totalAmount;
    private String currency;
    private SharePurchaseStatus status;
    private UUID requestedBy;
    private Instant requestedAt;
    private UUID reviewedBy;
    private String reviewedByName;
    private Instant reviewedAt;
    private String rejectionReason;
    private LocalDate paymentDate;
    private String paymentReference;
    private String evidenceFileKey;
    private String notes;
    private Instant createdAt;
    private Instant updatedAt;
    private UUID valuationSnapshotId;
    private ShareValuationResponse pricingValuation;
}
