package rw.terimbere.csams.modules.share.controller;

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
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import rw.terimbere.csams.modules.share.dto.SharePurchaseRequest;
import rw.terimbere.csams.modules.share.dto.SharePurchaseResponse;
import rw.terimbere.csams.modules.share.dto.SharePurchaseReviewRequest;
import rw.terimbere.csams.modules.share.dto.ShareValuationResponse;
import rw.terimbere.csams.modules.share.entity.SharePurchaseStatus;
import rw.terimbere.csams.modules.share.service.SharePurchaseService;
import rw.terimbere.csams.shared.common.dto.ApiResponse;
import rw.terimbere.csams.shared.common.dto.PageResponse;

@RestController
@RequestMapping("/api/v1/cooperatives/{cooperativeId}/shares")
@RequiredArgsConstructor
@Tag(name = "Shares", description = "Share valuation and purchase requests")
@SecurityRequirement(name = "bearerAuth")
public class SharePurchaseController {

    private final SharePurchaseService sharePurchaseService;

    @GetMapping("/valuation")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Current share price and eligible share capital base")
    public ResponseEntity<ApiResponse<ShareValuationResponse>> valuation(@PathVariable UUID cooperativeId) {
        return ResponseEntity.ok(ApiResponse.ok(sharePurchaseService.valuation(cooperativeId)));
    }

    @PostMapping("/purchases")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Submit a share purchase request (pending Accountant/President approval)")
    public ResponseEntity<ApiResponse<SharePurchaseResponse>> submit(
            @PathVariable UUID cooperativeId,
            @Valid @RequestBody SharePurchaseRequest request,
            HttpServletRequest httpRequest) {
        return ResponseEntity.ok(ApiResponse.ok(sharePurchaseService.submit(cooperativeId, request, httpRequest)));
    }

    @GetMapping("/purchases")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "List share purchases. Members see their own; reviewers can filter all.")
    public ResponseEntity<ApiResponse<PageResponse<SharePurchaseResponse>>> list(
            @PathVariable UUID cooperativeId,
            @RequestParam(required = false) UUID memberUserId,
            @RequestParam(required = false) SharePurchaseStatus status,
            @PageableDefault(size = 20, sort = "requestedAt", direction = Sort.Direction.DESC) Pageable pageable) {
        return ResponseEntity.ok(
                ApiResponse.ok(sharePurchaseService.list(cooperativeId, memberUserId, status, pageable)));
    }

    @GetMapping("/purchases/pending")
    @PreAuthorize(
            "hasAuthority('CONTRIBUTION_WRITE') and (hasRole('ACCOUNTANT') or hasRole('PRESIDENT') or hasRole('VICE_PRESIDENT') or hasRole('COOPERATIVE_ADMIN') or hasRole('SUPER_ADMIN'))")
    @Operation(summary = "List pending share purchases for Accountant/President review")
    public ResponseEntity<ApiResponse<PageResponse<SharePurchaseResponse>>> pending(
            @PathVariable UUID cooperativeId,
            @PageableDefault(size = 20, sort = "requestedAt", direction = Sort.Direction.DESC) Pageable pageable) {
        return ResponseEntity.ok(ApiResponse.ok(sharePurchaseService.pendingReview(cooperativeId, pageable)));
    }

    @PostMapping("/purchases/{purchaseId}/approve")
    @PreAuthorize(
            "hasAuthority('CONTRIBUTION_WRITE') and (hasRole('ACCOUNTANT') or hasRole('PRESIDENT') or hasRole('VICE_PRESIDENT') or hasRole('COOPERATIVE_ADMIN') or hasRole('SUPER_ADMIN'))")
    @Operation(summary = "Approve a pending share purchase and post the ledger credit")
    public ResponseEntity<ApiResponse<SharePurchaseResponse>> approve(
            @PathVariable UUID cooperativeId, @PathVariable UUID purchaseId, HttpServletRequest httpRequest) {
        return ResponseEntity.ok(
                ApiResponse.ok(sharePurchaseService.approve(cooperativeId, purchaseId, httpRequest)));
    }

    @PostMapping("/purchases/{purchaseId}/reject")
    @PreAuthorize(
            "hasAuthority('CONTRIBUTION_WRITE') and (hasRole('ACCOUNTANT') or hasRole('PRESIDENT') or hasRole('VICE_PRESIDENT') or hasRole('COOPERATIVE_ADMIN') or hasRole('SUPER_ADMIN'))")
    @Operation(summary = "Reject a pending share purchase")
    public ResponseEntity<ApiResponse<SharePurchaseResponse>> reject(
            @PathVariable UUID cooperativeId,
            @PathVariable UUID purchaseId,
            @Valid @RequestBody(required = false) SharePurchaseReviewRequest request,
            HttpServletRequest httpRequest) {
        return ResponseEntity.ok(
                ApiResponse.ok(sharePurchaseService.reject(cooperativeId, purchaseId, request, httpRequest)));
    }
}
