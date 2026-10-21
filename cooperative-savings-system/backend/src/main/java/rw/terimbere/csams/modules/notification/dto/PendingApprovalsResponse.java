package rw.terimbere.csams.modules.notification.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PendingApprovalsResponse {

    @Builder.Default
    private long contributionPendingCount = 0;

    @Builder.Default
    private long loanPendingCount = 0;

    @Builder.Default
    private long loanSecondApprovalCount = 0;

    @Builder.Default
    private long sharePurchasePendingCount = 0;
}
