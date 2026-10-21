package rw.terimbere.csams.modules.share.service;

import jakarta.servlet.http.HttpServletRequest;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import rw.terimbere.csams.modules.audit.service.AuditService;
import rw.terimbere.csams.modules.contribution.ShareAmountCalculator;
import rw.terimbere.csams.modules.cooperative.entity.Cooperative;
import rw.terimbere.csams.modules.cooperative.repository.CooperativeRepository;
import rw.terimbere.csams.modules.ledger.service.LedgerService;
import rw.terimbere.csams.modules.membership.entity.CooperativeMembership;
import rw.terimbere.csams.modules.membership.repository.CooperativeMembershipRepository;
import rw.terimbere.csams.modules.notification.entity.NotificationType;
import rw.terimbere.csams.modules.notification.service.NotificationFacade;
import rw.terimbere.csams.modules.share.dto.SharePurchaseRequest;
import rw.terimbere.csams.modules.share.dto.SharePurchaseResponse;
import rw.terimbere.csams.modules.share.dto.SharePurchaseReviewRequest;
import rw.terimbere.csams.modules.share.dto.ShareValuationResponse;
import rw.terimbere.csams.modules.share.entity.SharePurchase;
import rw.terimbere.csams.modules.share.entity.SharePurchaseStatus;
import rw.terimbere.csams.modules.share.entity.ShareValuationSnapshot;
import rw.terimbere.csams.modules.share.entity.ShareValuationSnapshotReason;
import rw.terimbere.csams.modules.share.repository.SharePurchaseRepository;
import rw.terimbere.csams.modules.share.repository.ShareValuationSnapshotRepository;
import rw.terimbere.csams.modules.user.entity.User;
import rw.terimbere.csams.modules.user.repository.UserRepository;
import rw.terimbere.csams.security.CooperativeAuthorizationService;
import rw.terimbere.csams.security.CooperativeOfficerRoles;
import rw.terimbere.csams.security.UserPrincipal;
import rw.terimbere.csams.shared.auditing.AuditableAction;
import rw.terimbere.csams.shared.common.dto.PageResponse;
import rw.terimbere.csams.shared.exceptions.BusinessException;
import rw.terimbere.csams.shared.exceptions.ForbiddenException;
import rw.terimbere.csams.shared.exceptions.ResourceNotFoundException;
import rw.terimbere.csams.shared.exceptions.ValidationException;
import rw.terimbere.csams.shared.financial.LedgerTransactionType;
import rw.terimbere.csams.shared.pagination.PageMapper;
import rw.terimbere.csams.shared.utilities.MoneyUtils;

@Service
@RequiredArgsConstructor
public class SharePurchaseService {

    private final SharePurchaseRepository sharePurchaseRepository;
    private final ShareValuationSnapshotRepository snapshotRepository;
    private final ShareValuationService shareValuationService;
    private final CooperativeRepository cooperativeRepository;
    private final CooperativeMembershipRepository membershipRepository;
    private final UserRepository userRepository;
    private final CooperativeAuthorizationService authorizationService;
    private final LedgerService ledgerService;
    private final AuditService auditService;
    private final NotificationFacade notificationFacade;

    @Transactional(readOnly = true)
    public ShareValuationResponse valuation(UUID cooperativeId) {
        requireCooperative(cooperativeId);
        authorizationService.requireMembership(cooperativeId);
        return shareValuationService.calculate(cooperativeId);
    }

    @Transactional
    public SharePurchaseResponse submit(
            UUID cooperativeId, SharePurchaseRequest request, HttpServletRequest httpRequest) {
        Cooperative cooperative = requireCooperative(cooperativeId);
        UserPrincipal principal = authorizationService.currentPrincipal();
        authorizationService.requireMembership(cooperativeId);

        UUID memberUserId = request.getMemberUserId() == null ? principal.getId() : request.getMemberUserId();
        if (!principal.getId().equals(memberUserId) && !canSubmitForAnotherMember(principal)) {
            throw new ForbiddenException("Cannot submit share purchases for another member");
        }

        CooperativeMembership membership = requireActiveMembership(cooperativeId, memberUserId);
        int quantity = request.getNumberOfShares() == null ? 0 : request.getNumberOfShares();
        if (quantity < 1) {
            throw new ValidationException("Number of shares must be at least 1");
        }
        if (quantity > ShareAmountCalculator.MAX_SHARE_COUNT) {
            throw new ValidationException("Number of shares cannot exceed " + ShareAmountCalculator.MAX_SHARE_COUNT);
        }

        int owned = ShareAmountCalculator.ownedShareCount(membership.getShareCount());
        if (owned + quantity > ShareAmountCalculator.MAX_SHARE_COUNT) {
            throw new BusinessException(
                    "This purchase would exceed the maximum of " + ShareAmountCalculator.MAX_SHARE_COUNT + " shares");
        }
        if (sharePurchaseRepository.existsByCooperativeIdAndMemberUserIdAndStatus(
                cooperativeId, memberUserId, SharePurchaseStatus.PENDING)) {
            throw new BusinessException("A pending share purchase already exists for this member");
        }

        ShareValuationResponse valuation = shareValuationService.calculate(cooperativeId);
        if (!valuation.isCanPurchase()) {
            throw new BusinessException(
                    valuation.getPurchaseBlockedReason() == null
                            ? ShareValuationService.BASE_PRICE_REQUIRED
                            : valuation.getPurchaseBlockedReason());
        }

        BigDecimal pricePerShare = MoneyUtils.scaleForStorage(valuation.getCurrentShareValue());
        BigDecimal totalAmount = MoneyUtils.scaleForStorage(
                MoneyUtils.multiply(MoneyUtils.scale(pricePerShare), BigDecimal.valueOf(quantity)));

        if (request.getPaymentDate() == null) {
            throw new ValidationException("Payment date is required");
        }
        String evidenceFileKey = trimToNull(request.getEvidenceFileKey());
        if (evidenceFileKey == null) {
            throw new ValidationException("Payment proof is required");
        }

        SharePurchase purchase = SharePurchase.builder()
                .cooperativeId(cooperativeId)
                .memberUserId(memberUserId)
                .numberOfShares(quantity)
                .pricePerShare(pricePerShare)
                .totalAmount(totalAmount)
                .status(SharePurchaseStatus.PENDING)
                .requestedBy(principal.getId())
                .requestedAt(Instant.now())
                .paymentDate(request.getPaymentDate())
                .paymentReference(trimToNull(request.getPaymentReference()))
                .evidenceFileKey(evidenceFileKey)
                .notes(trimToNull(request.getNotes()))
                .build();
        purchase = sharePurchaseRepository.save(purchase);

        ShareValuationSnapshot snapshot = shareValuationService.persist(
                valuation,
                ShareValuationSnapshotReason.PURCHASE_PRICING,
                ShareValuationService.SOURCE_SHARE_PURCHASE,
                purchase.getId());
        purchase.setValuationSnapshotId(snapshot.getId());
        purchase = sharePurchaseRepository.save(purchase);

        auditService.record(
                principal.getId(),
                cooperativeId,
                AuditableAction.SHARE_PURCHASE_SUBMIT,
                "SharePurchase",
                purchase.getId(),
                null,
                "{\"shares\":" + quantity + ",\"pricePerShare\":\"" + pricePerShare + "\",\"totalAmount\":\""
                        + totalAmount + "\"}",
                clientIp(httpRequest),
                userAgent(httpRequest));
        notificationFacade.notifyUser(
                memberUserId,
                cooperativeId,
                NotificationType.SYSTEM,
                "Share purchase submitted",
                "Your request to buy " + quantity + " share(s) is pending Accountant, President, or Vice President review.",
                "SharePurchase",
                purchase.getId());
        return toResponse(purchase, cooperative);
    }

    @Transactional(readOnly = true)
    public PageResponse<SharePurchaseResponse> list(
            UUID cooperativeId, UUID memberUserId, SharePurchaseStatus status, Pageable pageable) {
        Cooperative cooperative = requireCooperative(cooperativeId);
        UserPrincipal principal = authorizationService.currentPrincipal();
        authorizationService.requireMembership(cooperativeId);

        boolean reviewer = canReviewSharePurchases(principal) || canSubmitForAnotherMember(principal);
        UUID scopedMember = memberUserId;
        if (!reviewer) {
            scopedMember = principal.getId();
        } else if (memberUserId != null && !membershipRepository.existsByCooperativeIdAndUserId(cooperativeId, memberUserId)) {
            throw new ValidationException("Member is not part of this cooperative");
        }

        Page<SharePurchase> page;
        if (scopedMember != null && status != null) {
            page = sharePurchaseRepository.findByCooperativeIdAndMemberUserIdAndStatusOrderByRequestedAtDesc(
                    cooperativeId, scopedMember, status, pageable);
        } else if (scopedMember != null) {
            page = sharePurchaseRepository.findByCooperativeIdAndMemberUserIdOrderByRequestedAtDesc(
                    cooperativeId, scopedMember, pageable);
        } else if (status != null) {
            page = sharePurchaseRepository.findByCooperativeIdAndStatusOrderByRequestedAtDesc(
                    cooperativeId, status, pageable);
        } else {
            page = sharePurchaseRepository.findByCooperativeIdOrderByRequestedAtDesc(cooperativeId, pageable);
        }
        return PageMapper.toPageResponse(page, p -> toResponse(p, cooperative));
    }

    @Transactional(readOnly = true)
    public PageResponse<SharePurchaseResponse> pendingReview(UUID cooperativeId, Pageable pageable) {
        Cooperative cooperative = requireCooperative(cooperativeId);
        UserPrincipal principal = authorizationService.currentPrincipal();
        requireReviewer(principal);
        authorizationService.requireMembership(cooperativeId);
        Page<SharePurchase> page = sharePurchaseRepository.findByCooperativeIdAndStatusOrderByRequestedAtDesc(
                cooperativeId, SharePurchaseStatus.PENDING, pageable);
        return PageMapper.toPageResponse(page, p -> toResponse(p, cooperative));
    }

    @Transactional
    public SharePurchaseResponse approve(
            UUID cooperativeId, UUID purchaseId, HttpServletRequest httpRequest) {
        Cooperative cooperative = requireCooperative(cooperativeId);
        UserPrincipal principal = authorizationService.currentPrincipal();
        requireReviewer(principal);
        authorizationService.requireMembership(cooperativeId);

        SharePurchase purchase = requirePurchase(cooperativeId, purchaseId);
        if (purchase.getStatus() != SharePurchaseStatus.PENDING) {
            throw new BusinessException("Only PENDING share purchases can be approved");
        }
        if (!StringUtils.hasText(purchase.getEvidenceFileKey())) {
            throw new BusinessException("Payment proof is required before approval");
        }
        if (principal.getId().equals(purchase.getRequestedBy()) && !principal.hasRole(CooperativeAuthorizationService.SUPER_ADMIN)) {
            throw new ForbiddenException("You cannot approve your own share purchase");
        }

        CooperativeMembership membership =
                requireActiveMembership(cooperativeId, purchase.getMemberUserId());
        int owned = ShareAmountCalculator.ownedShareCount(membership.getShareCount());
        int next = owned + purchase.getNumberOfShares();
        if (next > ShareAmountCalculator.MAX_SHARE_COUNT) {
            throw new BusinessException(
                    "Approval would exceed the maximum of " + ShareAmountCalculator.MAX_SHARE_COUNT + " shares");
        }

        purchase.setStatus(SharePurchaseStatus.APPROVED);
        purchase.setReviewedBy(principal.getId());
        purchase.setReviewedAt(Instant.now());
        purchase.setRejectionReason(null);
        purchase = sharePurchaseRepository.save(purchase);

        membership.setShareCount(next);
        membershipRepository.save(membership);

        ledgerService.appendApproved(LedgerService.AppendRequest.builder()
                .cooperativeId(cooperativeId)
                .memberUserId(purchase.getMemberUserId())
                .transactionType(LedgerTransactionType.SHARE_PURCHASE)
                .debitAmount(BigDecimal.ZERO)
                .creditAmount(purchase.getTotalAmount())
                .currency(cooperative.getCurrency())
                .transactionDate(LocalDate.now())
                .sourceEntityType(LedgerService.SOURCE_SHARE_PURCHASE)
                .sourceEntityId(purchase.getId())
                .description("Share purchase of " + purchase.getNumberOfShares() + " share(s)")
                .recordedBy(principal.getId())
                .approvedBy(principal.getId())
                .idempotencyKey(LedgerService.sharePurchaseKey(purchase.getId()))
                .build());

        // The SHARE_ISSUANCE snapshot must describe the whole approved transaction: the new shares AND the matching
        // cash credit. Both are written above in this same transaction; the valuation queries auto-flush them, so no
        // explicit flush is needed. Taking the snapshot any earlier records the new share count without the cash.
        shareValuationService.persist(
                shareValuationService.calculate(cooperative),
                ShareValuationSnapshotReason.SHARE_ISSUANCE,
                ShareValuationService.SOURCE_SHARE_PURCHASE,
                purchase.getId());

        auditService.record(
                principal.getId(),
                cooperativeId,
                AuditableAction.SHARE_PURCHASE_APPROVE,
                "SharePurchase",
                purchase.getId(),
                "{\"status\":\"PENDING\"}",
                "{\"status\":\"APPROVED\",\"shares\":" + purchase.getNumberOfShares() + ",\"ownedAfter\":" + next + "}",
                clientIp(httpRequest),
                userAgent(httpRequest));
        notificationFacade.notifyUser(
                purchase.getMemberUserId(),
                cooperativeId,
                NotificationType.SYSTEM,
                "Share purchase approved",
                "Your purchase of " + purchase.getNumberOfShares() + " share(s) was approved.",
                "SharePurchase",
                purchase.getId());
        return toResponse(purchase, cooperative);
    }

    @Transactional
    public SharePurchaseResponse reject(
            UUID cooperativeId,
            UUID purchaseId,
            SharePurchaseReviewRequest request,
            HttpServletRequest httpRequest) {
        Cooperative cooperative = requireCooperative(cooperativeId);
        UserPrincipal principal = authorizationService.currentPrincipal();
        requireReviewer(principal);
        authorizationService.requireMembership(cooperativeId);

        SharePurchase purchase = requirePurchase(cooperativeId, purchaseId);
        if (purchase.getStatus() != SharePurchaseStatus.PENDING) {
            throw new BusinessException("Only PENDING share purchases can be rejected");
        }
        if (principal.getId().equals(purchase.getRequestedBy())
                && !principal.hasRole(CooperativeAuthorizationService.SUPER_ADMIN)) {
            throw new ForbiddenException("You cannot reject your own share purchase");
        }

        String reason = request == null ? null : trimToNull(request.getRejectionReason());
        if (reason == null) {
            throw new ValidationException("Rejection reason is required");
        }

        purchase.setStatus(SharePurchaseStatus.REJECTED);
        purchase.setReviewedBy(principal.getId());
        purchase.setReviewedAt(Instant.now());
        purchase.setRejectionReason(reason);
        purchase = sharePurchaseRepository.save(purchase);

        auditService.record(
                principal.getId(),
                cooperativeId,
                AuditableAction.SHARE_PURCHASE_REJECT,
                "SharePurchase",
                purchase.getId(),
                "{\"status\":\"PENDING\"}",
                "{\"status\":\"REJECTED\"}",
                clientIp(httpRequest),
                userAgent(httpRequest));
        notificationFacade.notifyUser(
                purchase.getMemberUserId(),
                cooperativeId,
                NotificationType.SYSTEM,
                "Share purchase rejected",
                "Your share purchase was rejected: " + reason,
                "SharePurchase",
                purchase.getId());
        return toResponse(purchase, cooperative);
    }

    public static boolean canSubmitForAnotherMember(UserPrincipal principal) {
        return principal != null && principal.hasAuthority("CONTRIBUTION_WRITE");
    }

    /**
     * Accountant, President (including legacy COOPERATIVE_ADMIN), Vice President, and Super Admin.
     */
    public static boolean canReviewSharePurchases(UserPrincipal principal) {
        if (principal == null || !principal.hasAuthority("CONTRIBUTION_WRITE")) {
            return false;
        }
        return principal.hasRole(CooperativeAuthorizationService.SUPER_ADMIN)
                || principal.hasRole(CooperativeOfficerRoles.PRESIDENT)
                || principal.hasRole(CooperativeOfficerRoles.LEGACY_ADMIN)
                || principal.hasRole(CooperativeOfficerRoles.VICE_PRESIDENT)
                || principal.hasRole(CooperativeOfficerRoles.ACCOUNTANT);
    }

    private void requireReviewer(UserPrincipal principal) {
        if (!canReviewSharePurchases(principal)) {
            throw new ForbiddenException(
                    "Only an Accountant, President, or Vice President can review share purchases");
        }
    }

    private Cooperative requireCooperative(UUID cooperativeId) {
        return cooperativeRepository
                .findByIdAndDeletedFalse(cooperativeId)
                .orElseThrow(() -> new ResourceNotFoundException("Cooperative", cooperativeId));
    }

    private CooperativeMembership requireActiveMembership(UUID cooperativeId, UUID memberUserId) {
        CooperativeMembership membership = membershipRepository
                .findByCooperativeIdAndUserId(cooperativeId, memberUserId)
                .orElseThrow(() -> new ValidationException("Member is not part of this cooperative"));
        if (!"ACTIVE".equalsIgnoreCase(membership.getMembershipStatus())) {
            throw new BusinessException("Only ACTIVE members can buy shares");
        }
        return membership;
    }

    private SharePurchase requirePurchase(UUID cooperativeId, UUID purchaseId) {
        return sharePurchaseRepository
                .findByIdAndCooperativeId(purchaseId, cooperativeId)
                .orElseThrow(() -> new ResourceNotFoundException("SharePurchase", purchaseId));
    }

    private SharePurchaseResponse toResponse(SharePurchase purchase, Cooperative cooperative) {
        String memberName = userRepository
                .findByIdAndDeletedFalse(purchase.getMemberUserId())
                .map(User::getFullName)
                .orElse(null);
        String reviewerName = purchase.getReviewedBy() == null
                ? null
                : userRepository
                        .findByIdAndDeletedFalse(purchase.getReviewedBy())
                        .map(User::getFullName)
                        .orElse(null);
        return SharePurchaseResponse.builder()
                .id(purchase.getId())
                .cooperativeId(purchase.getCooperativeId())
                .memberUserId(purchase.getMemberUserId())
                .memberName(memberName)
                .numberOfShares(purchase.getNumberOfShares())
                .pricePerShare(MoneyUtils.scale(purchase.getPricePerShare()))
                .currentShareValue(MoneyUtils.scale(purchase.getPricePerShare()))
                .totalAmount(MoneyUtils.scale(purchase.getTotalAmount()))
                .currency(cooperative.getCurrency() == null ? "RWF" : cooperative.getCurrency())
                .status(purchase.getStatus())
                .requestedBy(purchase.getRequestedBy())
                .requestedAt(purchase.getRequestedAt())
                .reviewedBy(purchase.getReviewedBy())
                .reviewedByName(reviewerName)
                .reviewedAt(purchase.getReviewedAt())
                .rejectionReason(purchase.getRejectionReason())
                .paymentDate(purchase.getPaymentDate())
                .paymentReference(purchase.getPaymentReference())
                .evidenceFileKey(purchase.getEvidenceFileKey())
                .notes(purchase.getNotes())
                .createdAt(purchase.getCreatedAt())
                .updatedAt(purchase.getUpdatedAt())
                .valuationSnapshotId(purchase.getValuationSnapshotId())
                .pricingValuation(pricingValuation(purchase, cooperative))
                .build();
    }

    private ShareValuationResponse pricingValuation(SharePurchase purchase, Cooperative cooperative) {
        if (purchase.getValuationSnapshotId() == null) {
            return null;
        }
        return snapshotRepository
                .findById(purchase.getValuationSnapshotId())
                .map(snapshot -> shareValuationService.toResponse(snapshot, cooperative))
                .orElse(null);
    }

    private static String trimToNull(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        return value.trim();
    }

    private static String clientIp(HttpServletRequest request) {
        if (request == null) {
            return null;
        }
        String forwarded = request.getHeader("X-Forwarded-For");
        if (StringUtils.hasText(forwarded)) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }

    private static String userAgent(HttpServletRequest request) {
        return request == null ? null : request.getHeader("User-Agent");
    }
}
