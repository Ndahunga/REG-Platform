package rw.terimbere.csams.modules.report.dto;

import java.math.BigDecimal;
import java.util.UUID;
import lombok.Builder;
import lombok.Value;

/**
 * Member-level contribution totals for FULL_FINANCIAL schedules (obligation year/month periods).
 */
@Value
@Builder
public class MemberContributionAggregate {
    UUID memberUserId;
    String memberName;
    BigDecimal expectedAmount;
    BigDecimal paidAmount;
    BigDecimal remainingAmount;
    BigDecimal overpaidAmount;
    long periodsCounted;
    /** Present only for single-month detail mode. */
    String status;
}
