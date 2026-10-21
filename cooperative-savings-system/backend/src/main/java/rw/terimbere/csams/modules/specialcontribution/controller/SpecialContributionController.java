package rw.terimbere.csams.modules.specialcontribution.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import rw.terimbere.csams.modules.specialcontribution.dto.SpecialCampaignRequest;
import rw.terimbere.csams.modules.specialcontribution.dto.SpecialCampaignResponse;
import rw.terimbere.csams.modules.specialcontribution.dto.SpecialCampaignStatusUpdateRequest;
import rw.terimbere.csams.modules.specialcontribution.dto.SpecialContributionResponse;
import rw.terimbere.csams.modules.specialcontribution.dto.SpecialContributionReviewRequest;
import rw.terimbere.csams.modules.specialcontribution.dto.SpecialContributionSubmitRequest;
import rw.terimbere.csams.modules.specialcontribution.entity.SpecialCampaignStatus;
import rw.terimbere.csams.modules.specialcontribution.entity.SpecialContributionStatus;
import rw.terimbere.csams.modules.specialcontribution.service.SpecialContributionService;
import rw.terimbere.csams.shared.common.dto.ApiResponse;

@RestController
@RequestMapping("/api/v1/cooperatives/{cooperativeId}/special-campaigns")
@RequiredArgsConstructor
@Tag(name = "Special Contributions", description = "Special contribution campaigns")
@SecurityRequirement(name = "bearerAuth")
public class SpecialContributionController {

    private final SpecialContributionService specialContributionService;

    @PostMapping
    @PreAuthorize("hasAuthority('CONTRIBUTION_WRITE')")
    @Operation(summary = "Create special contribution campaign")
    public ResponseEntity<ApiResponse<SpecialCampaignResponse>> create(
            @PathVariable UUID cooperativeId,
            @Valid @RequestBody SpecialCampaignRequest request,
            HttpServletRequest httpRequest) {
        return ResponseEntity.ok(
                ApiResponse.ok(specialContributionService.createCampaign(cooperativeId, request, httpRequest)));
    }

    @GetMapping
    @PreAuthorize("hasAuthority('CONTRIBUTION_READ')")
    @Operation(summary = "List special contribution campaigns")
    public ResponseEntity<ApiResponse<List<SpecialCampaignResponse>>> list(
            @PathVariable UUID cooperativeId, @RequestParam(required = false) SpecialCampaignStatus status) {
        return ResponseEntity.ok(ApiResponse.ok(specialContributionService.listCampaigns(cooperativeId, status)));
    }

    @GetMapping("/{campaignId}")
    @PreAuthorize("hasAuthority('CONTRIBUTION_READ')")
    @Operation(summary = "Get special contribution campaign")
    public ResponseEntity<ApiResponse<SpecialCampaignResponse>> get(
            @PathVariable UUID cooperativeId, @PathVariable UUID campaignId) {
        return ResponseEntity.ok(ApiResponse.ok(specialContributionService.getCampaign(cooperativeId, campaignId)));
    }

    @PutMapping("/{campaignId}")
    @PreAuthorize("hasAuthority('CONTRIBUTION_WRITE')")
    @Operation(summary = "Update special contribution campaign")
    public ResponseEntity<ApiResponse<SpecialCampaignResponse>> update(
            @PathVariable UUID cooperativeId,
            @PathVariable UUID campaignId,
            @Valid @RequestBody SpecialCampaignRequest request,
            HttpServletRequest httpRequest) {
        return ResponseEntity.ok(ApiResponse.ok(
                specialContributionService.updateCampaign(cooperativeId, campaignId, request, httpRequest)));
    }

    @PatchMapping("/{campaignId}/status")
    @PreAuthorize("hasAuthority('CONTRIBUTION_WRITE')")
    @Operation(summary = "Activate, close, or cancel a campaign")
    public ResponseEntity<ApiResponse<SpecialCampaignResponse>> updateStatus(
            @PathVariable UUID cooperativeId,
            @PathVariable UUID campaignId,
            @Valid @RequestBody SpecialCampaignStatusUpdateRequest request,
            HttpServletRequest httpRequest) {
        return ResponseEntity.ok(ApiResponse.ok(
                specialContributionService.updateCampaignStatus(cooperativeId, campaignId, request, httpRequest)));
    }

    @PostMapping("/{campaignId}/contributions")
    @PreAuthorize("hasAuthority('CONTRIBUTION_READ')")
    @Operation(summary = "Submit a special contribution (pending approval)")
    public ResponseEntity<ApiResponse<SpecialContributionResponse>> submit(
            @PathVariable UUID cooperativeId,
            @PathVariable UUID campaignId,
            @Valid @RequestBody SpecialContributionSubmitRequest request,
            HttpServletRequest httpRequest) {
        return ResponseEntity.ok(ApiResponse.ok(
                specialContributionService.submitContribution(cooperativeId, campaignId, request, httpRequest)));
    }

    @GetMapping("/{campaignId}/contributions")
    @PreAuthorize("hasAuthority('CONTRIBUTION_READ')")
    @Operation(summary = "List special contributions for a campaign")
    public ResponseEntity<ApiResponse<List<SpecialContributionResponse>>> listContributions(
            @PathVariable UUID cooperativeId,
            @PathVariable UUID campaignId,
            @RequestParam(required = false) SpecialContributionStatus status) {
        return ResponseEntity.ok(
                ApiResponse.ok(specialContributionService.listContributions(cooperativeId, campaignId, status)));
    }

    @PostMapping("/{campaignId}/contributions/{contribId}/approve")
    @PreAuthorize("hasAuthority('CONTRIBUTION_WRITE')")
    @Operation(summary = "Approve a special contribution and post ledger credit")
    public ResponseEntity<ApiResponse<SpecialContributionResponse>> approve(
            @PathVariable UUID cooperativeId,
            @PathVariable UUID campaignId,
            @PathVariable UUID contribId,
            @RequestBody(required = false) SpecialContributionReviewRequest request,
            HttpServletRequest httpRequest) {
        return ResponseEntity.ok(ApiResponse.ok(
                specialContributionService.approve(cooperativeId, campaignId, contribId, request, httpRequest)));
    }

    @PostMapping("/{campaignId}/contributions/{contribId}/reject")
    @PreAuthorize("hasAuthority('CONTRIBUTION_WRITE')")
    @Operation(summary = "Reject a special contribution")
    public ResponseEntity<ApiResponse<SpecialContributionResponse>> reject(
            @PathVariable UUID cooperativeId,
            @PathVariable UUID campaignId,
            @PathVariable UUID contribId,
            @RequestBody(required = false) SpecialContributionReviewRequest request,
            HttpServletRequest httpRequest) {
        return ResponseEntity.ok(ApiResponse.ok(
                specialContributionService.reject(cooperativeId, campaignId, contribId, request, httpRequest)));
    }
}
