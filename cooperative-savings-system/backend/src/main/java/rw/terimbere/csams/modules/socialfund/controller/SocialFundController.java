package rw.terimbere.csams.modules.socialfund.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import rw.terimbere.csams.modules.socialfund.dto.SocialContributionCreateRequest;
import rw.terimbere.csams.modules.socialfund.dto.SocialContributionResponse;
import rw.terimbere.csams.modules.socialfund.dto.SocialDisbursementCreateRequest;
import rw.terimbere.csams.modules.socialfund.dto.SocialDisbursementResponse;
import rw.terimbere.csams.modules.socialfund.dto.SocialFundReportResponse;
import rw.terimbere.csams.modules.socialfund.dto.SocialFundSettingsResponse;
import rw.terimbere.csams.modules.socialfund.dto.SocialFundSettingsUpdateRequest;
import rw.terimbere.csams.modules.socialfund.dto.SocialFundSummaryResponse;
import rw.terimbere.csams.modules.socialfund.dto.SocialReviewRequest;
import rw.terimbere.csams.modules.socialfund.entity.SocialContributionStatus;
import rw.terimbere.csams.modules.socialfund.entity.SocialDisbursementStatus;
import rw.terimbere.csams.modules.socialfund.service.SocialFundService;
import rw.terimbere.csams.modules.socialfund.service.SocialFundSettingsService;
import rw.terimbere.csams.shared.common.dto.ApiResponse;
import rw.terimbere.csams.shared.common.dto.PageResponse;

@RestController
@RequestMapping("/api/v1/cooperatives/{cooperativeId}/social-fund")
@RequiredArgsConstructor
@Tag(name = "Social Fund", description = "Separate solidarity fund: contributions and disbursements")
@SecurityRequirement(name = "bearerAuth")
public class SocialFundController {

    private final SocialFundService socialFundService;
    private final SocialFundSettingsService settingsService;

    @GetMapping("/summary")
    @PreAuthorize("hasAuthority('SOCIAL_READ')")
    @Operation(summary = "Social fund balance summary")
    public ResponseEntity<ApiResponse<SocialFundSummaryResponse>> summary(@PathVariable UUID cooperativeId) {
        return ResponseEntity.ok(ApiResponse.ok(socialFundService.summary(cooperativeId)));
    }

    @GetMapping("/settings")
    @PreAuthorize("hasAuthority('SOCIAL_READ')")
    @Operation(summary = "Get social fund settings (creates defaults if missing)")
    public ResponseEntity<ApiResponse<SocialFundSettingsResponse>> getSettings(@PathVariable UUID cooperativeId) {
        return ResponseEntity.ok(ApiResponse.ok(settingsService.getOrCreate(cooperativeId)));
    }

    @PutMapping("/settings")
    @PreAuthorize("hasAuthority('SOCIAL_WRITE')")
    @Operation(summary = "Update social fund settings")
    public ResponseEntity<ApiResponse<SocialFundSettingsResponse>> updateSettings(
            @PathVariable UUID cooperativeId,
            @Valid @RequestBody SocialFundSettingsUpdateRequest request,
            HttpServletRequest httpRequest) {
        return ResponseEntity.ok(ApiResponse.ok(settingsService.update(cooperativeId, request, httpRequest)));
    }

    @GetMapping("/contributions")
    @PreAuthorize("hasAuthority('SOCIAL_READ')")
    @Operation(summary = "List social contributions")
    public ResponseEntity<ApiResponse<PageResponse<SocialContributionResponse>>> listContributions(
            @PathVariable UUID cooperativeId,
            @RequestParam(required = false) SocialContributionStatus status,
            @RequestParam(required = false) UUID memberUserId,
            @PageableDefault(size = 20) Pageable pageable) {
        return ResponseEntity.ok(
                ApiResponse.ok(socialFundService.listContributions(cooperativeId, status, memberUserId, pageable)));
    }

    @GetMapping("/contributions/my")
    @PreAuthorize("hasAuthority('SOCIAL_READ')")
    @Operation(summary = "List current user's social contributions")
    public ResponseEntity<ApiResponse<List<SocialContributionResponse>>> myContributions(
            @PathVariable UUID cooperativeId) {
        return ResponseEntity.ok(ApiResponse.ok(socialFundService.myContributions(cooperativeId)));
    }

    @PostMapping("/contributions")
    @PreAuthorize("hasAuthority('SOCIAL_READ')")
    @Operation(summary = "Submit a social contribution (self or admin for others with SOCIAL_WRITE)")
    public ResponseEntity<ApiResponse<SocialContributionResponse>> submitContribution(
            @PathVariable UUID cooperativeId,
            @Valid @RequestBody SocialContributionCreateRequest request,
            HttpServletRequest httpRequest) {
        return ResponseEntity.ok(
                ApiResponse.ok(socialFundService.submitContribution(cooperativeId, request, httpRequest)));
    }

    @PostMapping("/contributions/{contributionId}/approve")
    @PreAuthorize("hasAuthority('SOCIAL_WRITE')")
    @Operation(summary = "Approve a pending social contribution (posts ledger credit)")
    public ResponseEntity<ApiResponse<SocialContributionResponse>> approveContribution(
            @PathVariable UUID cooperativeId,
            @PathVariable UUID contributionId,
            @RequestBody(required = false) SocialReviewRequest request,
            HttpServletRequest httpRequest) {
        return ResponseEntity.ok(ApiResponse.ok(
                socialFundService.approveContribution(cooperativeId, contributionId, request, httpRequest)));
    }

    @PostMapping("/contributions/{contributionId}/reject")
    @PreAuthorize("hasAuthority('SOCIAL_WRITE')")
    @Operation(summary = "Reject a pending social contribution")
    public ResponseEntity<ApiResponse<SocialContributionResponse>> rejectContribution(
            @PathVariable UUID cooperativeId,
            @PathVariable UUID contributionId,
            @RequestBody(required = false) SocialReviewRequest request,
            HttpServletRequest httpRequest) {
        return ResponseEntity.ok(ApiResponse.ok(
                socialFundService.rejectContribution(cooperativeId, contributionId, request, httpRequest)));
    }

    @GetMapping("/disbursements")
    @PreAuthorize("hasAuthority('SOCIAL_READ')")
    @Operation(summary = "List social disbursements")
    public ResponseEntity<ApiResponse<PageResponse<SocialDisbursementResponse>>> listDisbursements(
            @PathVariable UUID cooperativeId,
            @RequestParam(required = false) SocialDisbursementStatus status,
            @RequestParam(required = false) UUID beneficiaryMemberUserId,
            @PageableDefault(size = 20) Pageable pageable) {
        return ResponseEntity.ok(ApiResponse.ok(
                socialFundService.listDisbursements(cooperativeId, status, beneficiaryMemberUserId, pageable)));
    }

    @PostMapping("/disbursements")
    @PreAuthorize("hasAuthority('SOCIAL_WRITE')")
    @Operation(summary = "Request a social disbursement (PENDING)")
    public ResponseEntity<ApiResponse<SocialDisbursementResponse>> requestDisbursement(
            @PathVariable UUID cooperativeId,
            @Valid @RequestBody SocialDisbursementCreateRequest request,
            HttpServletRequest httpRequest) {
        return ResponseEntity.ok(
                ApiResponse.ok(socialFundService.requestDisbursement(cooperativeId, request, httpRequest)));
    }

    @PostMapping("/disbursements/{disbursementId}/approve")
    @PreAuthorize("hasAuthority('FUND_AUTHORIZE')")
    @Operation(summary = "Approve disbursement if social fund balance is sufficient (posts ledger debit)")
    public ResponseEntity<ApiResponse<SocialDisbursementResponse>> approveDisbursement(
            @PathVariable UUID cooperativeId,
            @PathVariable UUID disbursementId,
            @RequestBody(required = false) SocialReviewRequest request,
            HttpServletRequest httpRequest) {
        return ResponseEntity.ok(ApiResponse.ok(
                socialFundService.approveDisbursement(cooperativeId, disbursementId, request, httpRequest)));
    }

    @PostMapping("/disbursements/{disbursementId}/reject")
    @PreAuthorize("hasAuthority('SOCIAL_WRITE')")
    @Operation(summary = "Reject a pending social disbursement")
    public ResponseEntity<ApiResponse<SocialDisbursementResponse>> rejectDisbursement(
            @PathVariable UUID cooperativeId,
            @PathVariable UUID disbursementId,
            @RequestBody(required = false) SocialReviewRequest request,
            HttpServletRequest httpRequest) {
        return ResponseEntity.ok(ApiResponse.ok(
                socialFundService.rejectDisbursement(cooperativeId, disbursementId, request, httpRequest)));
    }

    @PostMapping("/disbursements/{disbursementId}/cancel")
    @PreAuthorize("hasAuthority('SOCIAL_WRITE')")
    @Operation(summary = "Cancel a pending social disbursement")
    public ResponseEntity<ApiResponse<SocialDisbursementResponse>> cancelDisbursement(
            @PathVariable UUID cooperativeId,
            @PathVariable UUID disbursementId,
            HttpServletRequest httpRequest) {
        return ResponseEntity.ok(
                ApiResponse.ok(socialFundService.cancelDisbursement(cooperativeId, disbursementId, httpRequest)));
    }

    @GetMapping("/report")
    @PreAuthorize("hasAuthority('SOCIAL_READ')")
    @Operation(summary = "Social fund report for a date range (JSON)")
    public ResponseEntity<ApiResponse<SocialFundReportResponse>> report(
            @PathVariable UUID cooperativeId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return ResponseEntity.ok(ApiResponse.ok(socialFundService.report(cooperativeId, from, to)));
    }
}
