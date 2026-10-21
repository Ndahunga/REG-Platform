package rw.terimbere.csams.modules.share.service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.EnumSet;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import rw.terimbere.csams.modules.cooperative.entity.Cooperative;
import rw.terimbere.csams.modules.cooperative.repository.CooperativeRepository;
import rw.terimbere.csams.modules.fine.entity.FineStatus;
import rw.terimbere.csams.modules.fine.repository.FineRepository;
import rw.terimbere.csams.modules.loan.entity.LoanStatus;
import rw.terimbere.csams.modules.loan.repository.LoanRepository;
import rw.terimbere.csams.modules.membership.repository.CooperativeMembershipRepository;
import rw.terimbere.csams.modules.settings.entity.CooperativeSettings;
import rw.terimbere.csams.modules.settings.repository.CooperativeSettingsRepository;
import rw.terimbere.csams.modules.share.dto.ShareValuationResponse;
import rw.terimbere.csams.modules.share.entity.ShareValuationSnapshot;
import rw.terimbere.csams.modules.share.entity.ShareValuationSnapshotReason;
import rw.terimbere.csams.modules.share.repository.ShareValuationSnapshotRepository;
import rw.terimbere.csams.shared.exceptions.ResourceNotFoundException;
import rw.terimbere.csams.shared.financial.LedgerFinancialCalculationService;
import rw.terimbere.csams.shared.utilities.MoneyUtils;

/**
 * Single source of truth for current share value.
 *
 * <pre>
 * totalIkiminaValue =
 *     availableFunds          // LedgerFinancialCalculationService.calculateAvailableGroupFund
 *   + outstandingLoans        // ACTIVE/OVERDUE loan.outstandingPrincipal
 *   + unpaidInterest          // ACTIVE/OVERDUE loan.outstandingInterest
 *   + unpaidPenalties         // UNPAID/PARTIALLY_PAID fine.outstandingAmount, except fines of WRITTEN_OFF loans
 *   + otherAssets             // remaining capital on ACTIVE/PARTIALLY_RETURNED investments
 *   − liabilities             // rotating funds collected and not yet paid to their recipient (0 for an accumulating scheme)
 *
 * currentShareValue = totalIkiminaValue / totalExistingShares
 * </pre>
 *
 * <p>When {@code totalExistingShares == 0}, {@code baseSharePrice} is used only as a bootstrap
 * price. GET /valuation never writes a snapshot.
 *
 * <p>Unpaid interest is the remaining contracted FLAT charge booked at disbursement. The domain
 * does not accrue interest over time, so this cannot be split into earned-to-date vs unearned
 * remainder of term.
 */
@Service
@RequiredArgsConstructor
public class ShareValuationService {

    public static final String BASE_PRICE_REQUIRED =
            "An initial base share price must be configured before the first share can be purchased";
    public static final String SOURCE_SHARE_PURCHASE = "SharePurchase";

    private final CooperativeRepository cooperativeRepository;
    private final CooperativeMembershipRepository membershipRepository;
    private final CooperativeSettingsRepository settingsRepository;
    private final LedgerFinancialCalculationService ledgerFinancialCalculationService;
    private final LoanRepository loanRepository;
    private final FineRepository fineRepository;
    private final ShareValuationSnapshotRepository snapshotRepository;
    private final rw.terimbere.csams.modules.rotation.payout.RotationEntitlementService rotationEntitlementService;

    @Transactional(readOnly = true)
    public ShareValuationResponse calculate(UUID cooperativeId) {
        Cooperative cooperative = cooperativeRepository
                .findByIdAndDeletedFalse(cooperativeId)
                .orElseThrow(() -> new ResourceNotFoundException("Cooperative", cooperativeId));
        return calculate(cooperative);
    }

    @Transactional(readOnly = true)
    public ShareValuationResponse calculate(Cooperative cooperative) {
        UUID cooperativeId = cooperative.getId();

        BigDecimal availableFunds = ledgerFinancialCalculationService.calculateAvailableGroupFund(cooperativeId);
        BigDecimal outstandingLoans = scaleOrZero(loanRepository.sumOutstandingPrincipalByStatuses(
                cooperativeId, EnumSet.of(LoanStatus.ACTIVE, LoanStatus.OVERDUE)));
        BigDecimal unpaidInterest = scaleOrZero(loanRepository.sumOutstandingInterestByStatuses(
                cooperativeId, EnumSet.of(LoanStatus.ACTIVE, LoanStatus.OVERDUE)));
        BigDecimal unpaidPenalties = scaleOrZero(fineRepository.sumOutstandingForValuationExcludingWrittenOffLoans(
                cooperativeId, EnumSet.of(FineStatus.UNPAID, FineStatus.PARTIALLY_PAID)));
        BigDecimal otherAssets = ledgerFinancialCalculationService.sumActiveInvestmentCapital(cooperativeId);
        // Rotating money that was collected but not yet paid out belongs to its recipient, not to the members' share
        // capital. It is inside available funds (it is cash), so it is subtracted here as a liability, in two disjoint
        // parts: what recipients are owed out of VERIFIED money, and rotating money not yet reconciled (identified as
        // rotating, so never retained capital merely because its verification is pending, and payout-ineligible until
        // reconciled). Each rupee is in at most one part. An accumulating scheme has no rotating collections, so both
        // are zero and its valuation is exactly what it was.
        BigDecimal verifiedRotatingLiabilities =
                MoneyUtils.scale(rotationEntitlementService.totalOutstandingEntitlement(cooperativeId));
        BigDecimal unreconciledRotatingFunds =
                MoneyUtils.scale(rotationEntitlementService.totalUnreconciledRotating(cooperativeId));
        var unresolved = rotationEntitlementService.unresolvedHistory(cooperativeId, cooperative.getOperatingModel());
        BigDecimal unresolvedRotatingFunds = MoneyUtils.scale(unresolved.knownRotating());
        BigDecimal unclassifiedFunds = MoneyUtils.scale(unresolved.unknown());
        // known-rotating money whose period is unresolved is held for recipients as well: never member-owned capital
        BigDecimal liabilities = MoneyUtils.add(
                MoneyUtils.add(verifiedRotatingLiabilities, unreconciledRotatingFunds), unresolvedRotatingFunds);

        BigDecimal totalIkiminaValue = MoneyUtils.subtract(
                MoneyUtils.add(
                        MoneyUtils.add(availableFunds, outstandingLoans),
                        MoneyUtils.add(MoneyUtils.add(unpaidInterest, unpaidPenalties), otherAssets)),
                liabilities);

        Number issued = membershipRepository.sumShareCountByCooperativeIdAndActiveStatus(cooperativeId);
        long totalExistingShares = issued == null ? 0L : issued.longValue();

        BigDecimal configuredBase = settingsRepository
                .findByCooperativeId(cooperativeId)
                .map(CooperativeSettings::getBaseSharePrice)
                .filter(price -> price != null && price.compareTo(BigDecimal.ZERO) > 0)
                .map(MoneyUtils::scale)
                .orElse(null);

        boolean usedBaseSharePrice = false;
        BigDecimal currentShareValue;
        String purchaseBlockedReason = null;

        if (totalExistingShares > 0) {
            currentShareValue = MoneyUtils.divide(totalIkiminaValue, BigDecimal.valueOf(totalExistingShares));
        } else if (configuredBase != null) {
            currentShareValue = configuredBase;
            usedBaseSharePrice = true;
        } else {
            currentShareValue = MoneyUtils.scale(BigDecimal.ZERO);
            purchaseBlockedReason = BASE_PRICE_REQUIRED;
        }

        boolean canPurchase = purchaseBlockedReason == null && currentShareValue.compareTo(BigDecimal.ZERO) > 0;
        if (purchaseBlockedReason == null && !canPurchase) {
            purchaseBlockedReason = "Current share value must be greater than zero before shares can be purchased";
        }

        return ShareValuationResponse.builder()
                .cooperativeId(cooperativeId)
                .currency(cooperative.getCurrency() == null ? "RWF" : cooperative.getCurrency())
                .availableFunds(availableFunds)
                .outstandingLoans(outstandingLoans)
                .unpaidInterest(unpaidInterest)
                .unpaidPenalties(unpaidPenalties)
                .otherAssets(otherAssets)
                .liabilities(liabilities)
                .verifiedRotatingLiabilities(verifiedRotatingLiabilities)
                .unreconciledRotatingFunds(unreconciledRotatingFunds)
                .unresolvedRotatingFunds(unresolvedRotatingFunds)
                .unclassifiedFunds(unclassifiedFunds)
                .totalIkiminaValue(totalIkiminaValue)
                .totalExistingShares(totalExistingShares)
                .currentShareValue(currentShareValue)
                .calculatedAt(Instant.now())
                .baseSharePrice(configuredBase)
                .usedBaseSharePrice(usedBaseSharePrice)
                .canPurchase(canPurchase)
                .purchaseBlockedReason(purchaseBlockedReason)
                .build();
    }

    @Transactional
    public ShareValuationSnapshot persist(
            ShareValuationResponse valuation,
            ShareValuationSnapshotReason reason,
            String sourceEntityType,
            UUID sourceEntityId) {
        return snapshotRepository.save(ShareValuationSnapshot.builder()
                .cooperativeId(valuation.getCooperativeId())
                .calculatedAt(valuation.getCalculatedAt() == null ? Instant.now() : valuation.getCalculatedAt())
                .availableFunds(MoneyUtils.scaleForStorage(valuation.getAvailableFunds()))
                .outstandingLoans(MoneyUtils.scaleForStorage(valuation.getOutstandingLoans()))
                .unpaidInterest(MoneyUtils.scaleForStorage(valuation.getUnpaidInterest()))
                .unpaidPenalties(MoneyUtils.scaleForStorage(valuation.getUnpaidPenalties()))
                .otherAssets(MoneyUtils.scaleForStorage(valuation.getOtherAssets()))
                .liabilities(MoneyUtils.scaleForStorage(valuation.getLiabilities()))
                .totalIkiminaValue(MoneyUtils.scaleForStorage(valuation.getTotalIkiminaValue()))
                .totalExistingShares(valuation.getTotalExistingShares())
                .currentShareValue(MoneyUtils.scaleForStorage(valuation.getCurrentShareValue()))
                .reason(reason)
                .sourceEntityType(sourceEntityType)
                .sourceEntityId(sourceEntityId)
                .build());
    }

    public ShareValuationResponse toResponse(ShareValuationSnapshot snapshot, Cooperative cooperative) {
        return ShareValuationResponse.builder()
                .cooperativeId(snapshot.getCooperativeId())
                .currency(cooperative.getCurrency() == null ? "RWF" : cooperative.getCurrency())
                .availableFunds(MoneyUtils.scale(snapshot.getAvailableFunds()))
                .outstandingLoans(MoneyUtils.scale(snapshot.getOutstandingLoans()))
                .unpaidInterest(MoneyUtils.scale(snapshot.getUnpaidInterest()))
                .unpaidPenalties(MoneyUtils.scale(snapshot.getUnpaidPenalties()))
                .otherAssets(MoneyUtils.scale(snapshot.getOtherAssets()))
                .liabilities(MoneyUtils.scale(snapshot.getLiabilities()))
                .totalIkiminaValue(MoneyUtils.scale(snapshot.getTotalIkiminaValue()))
                .totalExistingShares(snapshot.getTotalExistingShares())
                .currentShareValue(MoneyUtils.scale(snapshot.getCurrentShareValue()))
                .calculatedAt(snapshot.getCalculatedAt())
                .usedBaseSharePrice(false)
                .canPurchase(false)
                .build();
    }

    private static BigDecimal scaleOrZero(BigDecimal amount) {
        return MoneyUtils.scale(amount == null ? BigDecimal.ZERO : amount);
    }
}
