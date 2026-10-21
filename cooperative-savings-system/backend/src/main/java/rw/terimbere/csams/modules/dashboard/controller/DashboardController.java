package rw.terimbere.csams.modules.dashboard.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import rw.terimbere.csams.modules.contribution.dto.MonthlyContributionChartPoint;
import rw.terimbere.csams.modules.dashboard.dto.DashboardAdvancedInsightsResponse;
import rw.terimbere.csams.modules.dashboard.dto.DashboardInsightsResponse;
import rw.terimbere.csams.modules.dashboard.dto.DashboardMemberInsightsResponse;
import rw.terimbere.csams.modules.dashboard.dto.DashboardSummaryResponse;
import rw.terimbere.csams.modules.dashboard.dto.InvestmentsByMonthPoint;
import rw.terimbere.csams.modules.dashboard.dto.LoansDisbursedByMonthPoint;
import rw.terimbere.csams.modules.dashboard.service.DashboardService;
import rw.terimbere.csams.shared.common.dto.ApiResponse;

@RestController
@RequestMapping("/api/v1/cooperatives/{cooperativeId}/dashboard")
@RequiredArgsConstructor
@Tag(name = "Dashboard", description = "Cooperative dashboard metrics")
@SecurityRequirement(name = "bearerAuth")
public class DashboardController {

    private final DashboardService dashboardService;

    @GetMapping("/summary")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Dashboard contribution and membership summary")
    public ResponseEntity<ApiResponse<DashboardSummaryResponse>> summary(@PathVariable UUID cooperativeId) {
        return ResponseEntity.ok(ApiResponse.ok(dashboardService.summary(cooperativeId)));
    }

    @GetMapping("/insights")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Current vs previous month operational analytics")
    public ResponseEntity<ApiResponse<DashboardInsightsResponse>> insights(@PathVariable UUID cooperativeId) {
        return ResponseEntity.ok(ApiResponse.ok(dashboardService.insights(cooperativeId)));
    }

    @GetMapping("/member-insights")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Officer-only member contribution, fine, and overdue loan insights")
    public ResponseEntity<ApiResponse<DashboardMemberInsightsResponse>> memberInsights(
            @PathVariable UUID cooperativeId) {
        return ResponseEntity.ok(ApiResponse.ok(dashboardService.memberInsights(cooperativeId)));
    }

    @GetMapping("/charts/monthly-contributions")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Monthly contribution totals chart data")
    public ResponseEntity<ApiResponse<List<MonthlyContributionChartPoint>>> monthlyContributions(
            @PathVariable UUID cooperativeId, @RequestParam int year) {
        return ResponseEntity.ok(
                ApiResponse.ok(dashboardService.monthlyContributionsChart(cooperativeId, year)));
    }

    @GetMapping("/charts/loans-disbursed-by-month")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Monthly disbursed loan count and principal chart data")
    public ResponseEntity<ApiResponse<List<LoansDisbursedByMonthPoint>>> loansDisbursedByMonth(
            @PathVariable UUID cooperativeId, @RequestParam int year) {
        return ResponseEntity.ok(
                ApiResponse.ok(dashboardService.loansDisbursedByMonthChart(cooperativeId, year)));
    }

    @GetMapping("/charts/investments-by-month")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Monthly investment capital deployed chart data")
    public ResponseEntity<ApiResponse<List<InvestmentsByMonthPoint>>> investmentsByMonth(
            @PathVariable UUID cooperativeId, @RequestParam int year) {
        return ResponseEntity.ok(
                ApiResponse.ok(dashboardService.investmentsByMonthChart(cooperativeId, year)));
    }

    @GetMapping("/advanced-insights")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Officer advanced seasonality companions: frequent borrowers and largest investments")
    public ResponseEntity<ApiResponse<DashboardAdvancedInsightsResponse>> advancedInsights(
            @PathVariable UUID cooperativeId) {
        return ResponseEntity.ok(ApiResponse.ok(dashboardService.advancedInsights(cooperativeId)));
    }
}
