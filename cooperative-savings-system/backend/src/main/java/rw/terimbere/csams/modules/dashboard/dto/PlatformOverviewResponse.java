package rw.terimbere.csams.modules.dashboard.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PlatformOverviewResponse {

    private long totalCooperatives;
    private long activeCooperatives;
    private long inactiveCooperatives;
    private long suspendedCooperatives;
    private long archivedCooperatives;
    private long totalMembers;
    private long activeMembers;
    private long totalUsers;
    private long pendingContributionReviews;
    private long pendingSpecialContributions;
    private long pendingLoans;
    private long overdueLoans;
    private long pendingFinePayments;
    private long pendingSocialContributions;
    private long pendingPayouts;
}
