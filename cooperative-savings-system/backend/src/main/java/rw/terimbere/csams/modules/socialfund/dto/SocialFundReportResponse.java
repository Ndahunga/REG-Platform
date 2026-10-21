package rw.terimbere.csams.modules.socialfund.dto;

import java.time.LocalDate;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SocialFundReportResponse {

    private LocalDate from;
    private LocalDate to;
    private SocialFundSummaryResponse summary;

    @Builder.Default
    private List<SocialContributionResponse> approvedContributions = List.of();

    @Builder.Default
    private List<SocialDisbursementResponse> approvedDisbursements = List.of();
}
