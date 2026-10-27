package rw.terimbere.csams.modules.member.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import rw.terimbere.csams.modules.member.dto.AssignAdministratorRequest;
import rw.terimbere.csams.modules.member.dto.MemberDetailResponse;
import rw.terimbere.csams.modules.member.dto.MemberFinancialSummaryResponse;
import rw.terimbere.csams.modules.member.dto.MemberRegisterRequest;
import rw.terimbere.csams.modules.member.dto.MemberResponse;
import rw.terimbere.csams.modules.member.dto.MemberStatusUpdateRequest;
import rw.terimbere.csams.modules.member.dto.MemberUpdateRequest;
import rw.terimbere.csams.modules.member.service.MemberService;
import rw.terimbere.csams.shared.common.dto.ApiResponse;
import rw.terimbere.csams.shared.common.dto.PageResponse;

@RestController
@RequiredArgsConstructor
@Tag(name = "Members", description = "Cooperative member management")
@SecurityRequirement(name = "bearerAuth")
public class MemberController {

    private final MemberService memberService;
    private final rw.terimbere.csams.modules.member.service.MemberRemovalService memberRemovalService;

    @PostMapping("/api/v1/cooperatives/{cooperativeId}/members")
    @PreAuthorize("hasAuthority('MEMBERSHIP_MANAGE') or hasAuthority('USER_WRITE')")
    @Operation(
            summary = "Register a member",
            description =
                    "Creates user + ACTIVE membership. JWT coopIds for the new member stay stale until they login/refresh;"
                            + " frontend should use GET /api/v1/cooperatives/mine for the selector.")
    public ResponseEntity<ApiResponse<MemberResponse>> register(
            @PathVariable UUID cooperativeId,
            @Valid @RequestBody MemberRegisterRequest request,
            HttpServletRequest httpRequest) {
        return ResponseEntity.ok(ApiResponse.ok(memberService.register(cooperativeId, request, httpRequest)));
    }

    @GetMapping("/api/v1/cooperatives/{cooperativeId}/members")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "List cooperative members")
    public ResponseEntity<ApiResponse<PageResponse<MemberResponse>>> list(
            @PathVariable UUID cooperativeId,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String status,
            @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable) {
        return ResponseEntity.ok(ApiResponse.ok(memberService.list(cooperativeId, q, status, pageable)));
    }

    @GetMapping("/api/v1/cooperatives/{cooperativeId}/members/financial-summaries")
    @PreAuthorize("hasAuthority('MEMBERSHIP_MANAGE') or hasAuthority('USER_WRITE')")
    @Operation(
            summary = "List member financial summaries",
            description = "Paginated dashboard rows for Member Financial Summary. Avoids N+1 HTTP calls from the client.")
    public ResponseEntity<ApiResponse<PageResponse<MemberFinancialSummaryResponse>>> listFinancialSummaries(
            @PathVariable UUID cooperativeId,
            @RequestParam(required = false) String q,
            @PageableDefault(size = 10, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable) {
        return ResponseEntity.ok(ApiResponse.ok(memberService.listFinancialSummaries(cooperativeId, q, pageable)));
    }

    @GetMapping("/api/v1/cooperatives/{cooperativeId}/members/{memberUserId}")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Get member detail")
    public ResponseEntity<ApiResponse<MemberDetailResponse>> getDetail(
            @PathVariable UUID cooperativeId, @PathVariable UUID memberUserId) {
        return ResponseEntity.ok(ApiResponse.ok(memberService.getDetail(cooperativeId, memberUserId)));
    }

    @GetMapping("/api/v1/cooperatives/{cooperativeId}/members/{memberUserId}/financial-summary")
    @PreAuthorize("isAuthenticated()")
    @Operation(
            summary = "Get member financial summary",
            description =
                    "Computed totals for contributions, loans, fines, social fund, and payouts. "
                            + "Members may view their own summary; MEMBERSHIP_MANAGE or SUPER_ADMIN may view others.")
    public ResponseEntity<ApiResponse<MemberFinancialSummaryResponse>> financialSummary(
            @PathVariable UUID cooperativeId, @PathVariable UUID memberUserId) {
        return ResponseEntity.ok(ApiResponse.ok(memberService.financialSummary(cooperativeId, memberUserId)));
    }

    @PutMapping("/api/v1/cooperatives/{cooperativeId}/members/{memberUserId}")
    @PreAuthorize("hasAuthority('MEMBERSHIP_MANAGE') or hasAuthority('USER_WRITE')")
    @Operation(summary = "Update member profile")
    public ResponseEntity<ApiResponse<MemberResponse>> update(
            @PathVariable UUID cooperativeId,
            @PathVariable UUID memberUserId,
            @Valid @RequestBody MemberUpdateRequest request,
            HttpServletRequest httpRequest) {
        return ResponseEntity.ok(
                ApiResponse.ok(memberService.update(cooperativeId, memberUserId, request, httpRequest)));
    }

    @PatchMapping("/api/v1/cooperatives/{cooperativeId}/members/{memberUserId}/status")
    @PreAuthorize("hasAuthority('MEMBERSHIP_MANAGE') or hasAuthority('USER_WRITE')")
    @Operation(summary = "Update member account and/or membership status")
    public ResponseEntity<ApiResponse<MemberResponse>> updateStatus(
            @PathVariable UUID cooperativeId,
            @PathVariable UUID memberUserId,
            @Valid @RequestBody MemberStatusUpdateRequest request,
            HttpServletRequest httpRequest) {
        return ResponseEntity.ok(
                ApiResponse.ok(memberService.updateStatus(cooperativeId, memberUserId, request, httpRequest)));
    }

    public record DeactivateRequest(String reason) {}

    @GetMapping("/api/v1/cooperatives/{cooperativeId}/members/{memberUserId}/removal-eligibility")
    @PreAuthorize("hasAuthority('MEMBERSHIP_MANAGE') or hasAuthority('USER_WRITE')")
    @Operation(summary = "What may be done to remove this member: delete (no history), deactivate or reactivate, and why not")
    public ResponseEntity<ApiResponse<rw.terimbere.csams.modules.member.service.MemberRemovalService.Eligibility>> removalEligibility(
            @PathVariable UUID cooperativeId, @PathVariable UUID memberUserId) {
        return ResponseEntity.ok(ApiResponse.ok(memberRemovalService.eligibility(cooperativeId, memberUserId)));
    }

    @PostMapping("/api/v1/cooperatives/{cooperativeId}/members/{memberUserId}/deactivate")
    @PreAuthorize("hasAuthority('MEMBERSHIP_MANAGE') or hasAuthority('USER_WRITE')")
    @Operation(summary = "Remove a member from active membership; every record, loan and obligation stays")
    public ResponseEntity<ApiResponse<MemberResponse>> deactivate(
            @PathVariable UUID cooperativeId,
            @PathVariable UUID memberUserId,
            @RequestBody DeactivateRequest request,
            HttpServletRequest httpRequest) {
        return ResponseEntity.ok(ApiResponse.ok(
                memberRemovalService.deactivate(cooperativeId, memberUserId, request == null ? null : request.reason(), httpRequest)));
    }

    @PostMapping("/api/v1/cooperatives/{cooperativeId}/members/{memberUserId}/reactivate")
    @PreAuthorize("hasAuthority('MEMBERSHIP_MANAGE') or hasAuthority('USER_WRITE')")
    @Operation(summary = "Restore an inactive member to active membership")
    public ResponseEntity<ApiResponse<MemberResponse>> reactivate(
            @PathVariable UUID cooperativeId, @PathVariable UUID memberUserId, HttpServletRequest httpRequest) {
        return ResponseEntity.ok(ApiResponse.ok(memberRemovalService.reactivate(cooperativeId, memberUserId, httpRequest)));
    }

    @DeleteMapping("/api/v1/cooperatives/{cooperativeId}/members/{memberUserId}")
    @PreAuthorize("hasAuthority('MEMBERSHIP_MANAGE') or hasAuthority('USER_WRITE')")
    @Operation(summary = "Permanently delete a member who has no history at all; refused otherwise")
    public ResponseEntity<ApiResponse<Void>> delete(
            @PathVariable UUID cooperativeId, @PathVariable UUID memberUserId, HttpServletRequest httpRequest) {
        memberRemovalService.delete(cooperativeId, memberUserId, httpRequest);
        return ResponseEntity.ok(ApiResponse.ok(null));
    }

    @GetMapping("/api/v1/cooperatives/{cooperativeId}/members/{memberUserId}/full-deletion")
    @PreAuthorize("hasAuthority('MEMBERSHIP_MANAGE') or hasAuthority('USER_WRITE')")
    @Operation(summary = "What deleting this member and all their records in the scheme would remove (nothing is changed)")
    public ResponseEntity<ApiResponse<rw.terimbere.csams.modules.member.service.MemberRemovalService.PurgePreview>> purgePreview(
            @PathVariable UUID cooperativeId, @PathVariable UUID memberUserId) {
        return ResponseEntity.ok(ApiResponse.ok(memberRemovalService.purgePreview(cooperativeId, memberUserId)));
    }

    @PostMapping("/api/v1/cooperatives/{cooperativeId}/members/{memberUserId}/full-deletion")
    @PreAuthorize("hasAuthority('MEMBERSHIP_MANAGE') or hasAuthority('USER_WRITE')")
    @Operation(summary = "President or Vice President: delete this member and all their records in the scheme for good")
    public ResponseEntity<ApiResponse<rw.terimbere.csams.modules.member.service.MemberRemovalService.PurgePreview>> purge(
            @PathVariable UUID cooperativeId,
            @PathVariable UUID memberUserId,
            @RequestBody rw.terimbere.csams.modules.member.service.MemberRemovalService.PurgeRequest request,
            HttpServletRequest httpRequest) {
        return ResponseEntity.ok(ApiResponse.ok(memberRemovalService.purge(cooperativeId, memberUserId, request, httpRequest)));
    }

    @PostMapping(
            value = "/api/v1/cooperatives/{cooperativeId}/members/{memberUserId}/profile-image",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Upload member profile image")
    public ResponseEntity<ApiResponse<MemberResponse>> uploadProfileImage(
            @PathVariable UUID cooperativeId,
            @PathVariable UUID memberUserId,
            @RequestParam("file") MultipartFile file,
            HttpServletRequest httpRequest) {
        return ResponseEntity.ok(ApiResponse.ok(
                memberService.uploadProfileImage(cooperativeId, memberUserId, file, httpRequest)));
    }

    @PostMapping("/api/v1/cooperatives/{id}/administrators")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    @Operation(summary = "Assign or create a cooperative administrator")
    public ResponseEntity<ApiResponse<MemberResponse>> assignAdministrator(
            @PathVariable("id") UUID cooperativeId,
            @Valid @RequestBody AssignAdministratorRequest request,
            HttpServletRequest httpRequest) {
        return ResponseEntity.ok(
                ApiResponse.ok(memberService.assignAdministrator(cooperativeId, request, httpRequest)));
    }
}
