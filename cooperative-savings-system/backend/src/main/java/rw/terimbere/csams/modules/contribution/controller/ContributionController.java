package rw.terimbere.csams.modules.contribution.controller;

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
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.format.annotation.DateTimeFormat;
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
import rw.terimbere.csams.modules.contribution.dto.ContributionBatchRequest;
import rw.terimbere.csams.modules.contribution.dto.ContributionPeriodPreviewResponse;
import rw.terimbere.csams.modules.contribution.dto.ContributionPeriodSummaryResponse;
import rw.terimbere.csams.modules.contribution.dto.ContributionResponse;
import rw.terimbere.csams.modules.contribution.dto.ContributionReviewRequest;
import rw.terimbere.csams.modules.contribution.dto.ContributionSubmitRequest;
import rw.terimbere.csams.modules.contribution.dto.ContributionUpdateRequest;
import rw.terimbere.csams.modules.contribution.entity.ContributionStatus;
import rw.terimbere.csams.modules.contribution.service.ContributionService;
import rw.terimbere.csams.shared.common.dto.ApiResponse;
import rw.terimbere.csams.shared.common.dto.PageResponse;

@RestController
@RequestMapping("/api/v1/cooperatives/{cooperativeId}/contributions")
@RequiredArgsConstructor
@Tag(name = "Contributions", description = "Monthly contribution recording and history")
@SecurityRequirement(name = "bearerAuth")
public class ContributionController {

    private final ContributionService contributionService;

    @GetMapping("/period")
    @PreAuthorize("hasAuthority('CONTRIBUTION_READ')")
    @Operation(summary = "Period contribution grid for ACTIVE members")
    public ResponseEntity<ApiResponse<List<ContributionResponse>>> periodGrid(
            @PathVariable UUID cooperativeId, @RequestParam int year, @RequestParam int month) {
        return ResponseEntity.ok(ApiResponse.ok(contributionService.getOrBuildPeriodGrid(cooperativeId, year, month)));
    }

    @PutMapping("/period")
    @PreAuthorize("hasAuthority('CONTRIBUTION_WRITE')")
    @Operation(summary = "Batch upsert contribution lines for a period")
    public ResponseEntity<ApiResponse<List<ContributionResponse>>> batchSave(
            @PathVariable UUID cooperativeId,
            @RequestParam int year,
            @RequestParam int month,
            @Valid @RequestBody ContributionBatchRequest request,
            HttpServletRequest httpRequest) {
        return ResponseEntity.ok(
                ApiResponse.ok(contributionService.batchSave(cooperativeId, year, month, request, httpRequest)));
    }

    @GetMapping
    @PreAuthorize("hasAuthority('CONTRIBUTION_READ')")
    @Operation(summary = "Contribution history (pageable)")
    public ResponseEntity<ApiResponse<PageResponse<ContributionResponse>>> history(
            @PathVariable UUID cooperativeId,
            @RequestParam(required = false) UUID memberUserId,
            @RequestParam(required = false) Integer year,
            @RequestParam(required = false) Integer month,
            @RequestParam(required = false) ContributionStatus status,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fromDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate toDate,
            @PageableDefault(size = 20, sort = "year", direction = Sort.Direction.DESC) Pageable pageable) {
        return ResponseEntity.ok(ApiResponse.ok(contributionService.history(
                cooperativeId, memberUserId, year, month, status, fromDate, toDate, pageable)));
    }

    @GetMapping("/my")
    @PreAuthorize("hasAuthority('CONTRIBUTION_READ')")
    @Operation(summary = "Current user's contribution history")
    public ResponseEntity<ApiResponse<PageResponse<ContributionResponse>>> myContributions(
            @PathVariable UUID cooperativeId,
            @RequestParam(required = false) Integer year,
            @RequestParam(required = false) Integer month,
            @RequestParam(required = false) ContributionStatus status,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fromDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate toDate,
            @PageableDefault(size = 20, sort = "year", direction = Sort.Direction.DESC) Pageable pageable) {
        return ResponseEntity.ok(ApiResponse.ok(contributionService.myContributions(
                cooperativeId, year, month, status, fromDate, toDate, pageable)));
    }

    @GetMapping("/my/period-preview")
    @PreAuthorize("hasAuthority('CONTRIBUTION_READ')")
    @Operation(summary = "Preview required contribution for a selected month")
    public ResponseEntity<ApiResponse<ContributionPeriodPreviewResponse>> periodPreview(
            @PathVariable UUID cooperativeId,
            @RequestParam(required = false) Integer year,
            @RequestParam(required = false) Integer month) {
        return ResponseEntity.ok(ApiResponse.ok(contributionService.periodPreview(cooperativeId, year, month)));
    }

    @PostMapping("/submissions")
    @PreAuthorize("hasAuthority('CONTRIBUTION_READ')")
    @Operation(summary = "Member submits a regular contribution for Accountant review")
    public ResponseEntity<ApiResponse<ContributionResponse>> submitMine(
            @PathVariable UUID cooperativeId,
            @Valid @RequestBody ContributionSubmitRequest request,
            HttpServletRequest httpRequest) {
        return ResponseEntity.ok(ApiResponse.ok(contributionService.submitMine(cooperativeId, request, httpRequest)));
    }

    @GetMapping("/pending-review")
    @PreAuthorize("hasAuthority('CONTRIBUTION_WRITE')")
    @Operation(summary = "List member contribution submissions awaiting Accountant review")
    public ResponseEntity<ApiResponse<PageResponse<ContributionResponse>>> pendingReview(
            @PathVariable UUID cooperativeId,
            @PageableDefault(size = 20, sort = "createdAt") Pageable pageable) {
        return ResponseEntity.ok(ApiResponse.ok(contributionService.pendingReview(cooperativeId, pageable)));
    }

    @PostMapping("/{id}/approve")
    @PreAuthorize("hasAuthority('CONTRIBUTION_WRITE')")
    @Operation(summary = "Approve a pending member contribution submission")
    public ResponseEntity<ApiResponse<ContributionResponse>> approveSubmission(
            @PathVariable UUID cooperativeId,
            @PathVariable UUID id,
            HttpServletRequest httpRequest) {
        return ResponseEntity.ok(
                ApiResponse.ok(contributionService.approveSubmission(cooperativeId, id, httpRequest)));
    }

    @PostMapping("/{id}/reject")
    @PreAuthorize("hasAuthority('CONTRIBUTION_WRITE')")
    @Operation(summary = "Reject a pending member contribution submission")
    public ResponseEntity<ApiResponse<ContributionResponse>> rejectSubmission(
            @PathVariable UUID cooperativeId,
            @PathVariable UUID id,
            @Valid @RequestBody(required = false) ContributionReviewRequest request,
            HttpServletRequest httpRequest) {
        return ResponseEntity.ok(
                ApiResponse.ok(contributionService.rejectSubmission(cooperativeId, id, request, httpRequest)));
    }

    @GetMapping("/summary")
    @PreAuthorize("hasAuthority('CONTRIBUTION_READ')")
    @Operation(summary = "Contribution totals for a period")
    public ResponseEntity<ApiResponse<ContributionPeriodSummaryResponse>> summary(
            @PathVariable UUID cooperativeId,
            @RequestParam(required = false) Integer year,
            @RequestParam(required = false) Integer month) {
        return ResponseEntity.ok(ApiResponse.ok(contributionService.summary(cooperativeId, year, month)));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('CONTRIBUTION_READ')")
    @Operation(summary = "Get contribution by id")
    public ResponseEntity<ApiResponse<ContributionResponse>> getById(
            @PathVariable UUID cooperativeId, @PathVariable UUID id) {
        return ResponseEntity.ok(ApiResponse.ok(contributionService.getById(cooperativeId, id)));
    }

    @PatchMapping("/{id}")
    @PreAuthorize("hasAuthority('CONTRIBUTION_WRITE')")
    @Operation(summary = "Correct a contribution")
    public ResponseEntity<ApiResponse<ContributionResponse>> update(
            @PathVariable UUID cooperativeId,
            @PathVariable UUID id,
            @Valid @RequestBody ContributionUpdateRequest request,
            HttpServletRequest httpRequest) {
        return ResponseEntity.ok(
                ApiResponse.ok(contributionService.updateSingle(cooperativeId, id, request, httpRequest)));
    }
}
