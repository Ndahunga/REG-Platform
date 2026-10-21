package rw.terimbere.csams.modules.report.service;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import rw.terimbere.csams.modules.contribution.repository.ContributionRepository;
import rw.terimbere.csams.modules.cooperative.entity.Cooperative;
import rw.terimbere.csams.modules.cooperative.entity.SchemeOperatingModel;
import rw.terimbere.csams.modules.cooperative.repository.CooperativeRepository;
import rw.terimbere.csams.modules.membercredit.MemberCreditService;
import rw.terimbere.csams.modules.rotation.payout.RotationEntitlementService;
import rw.terimbere.csams.modules.rotation.payout.RotationEntitlementService.PeriodEntitlement;
import rw.terimbere.csams.modules.rotation.payout.RotationPayoutRepository;
import rw.terimbere.csams.modules.rotation.payout.RotationPayoutStatus;
import rw.terimbere.csams.security.CooperativeAuthorizationService;
import rw.terimbere.csams.security.UserPrincipal;
import rw.terimbere.csams.shared.exceptions.ForbiddenException;
import rw.terimbere.csams.shared.exceptions.ResourceNotFoundException;
import rw.terimbere.csams.shared.financial.LedgerFinancialCalculationService;
import rw.terimbere.csams.shared.utilities.MoneyUtils;

/**
 * Model-aware financial summary of a Saving Scheme. It separates what was EXPECTED (never cash), what was COLLECTED, how
 * the collection divides into retained and rotating money, what recipients are still owed, what was actually paid out,
 * and the contribution arrears.
 *
 * <p>Rotating collections are not member capital: while undistributed they are a liability to the recipients, and the
 * share valuation subtracts exactly that amount. For an Accumulating scheme every rotating figure is zero and nothing
 * changes.
 */
@Service
@RequiredArgsConstructor
public class ModelFinancialSummaryService {

    private static final ZoneId KIGALI = ZoneId.of("Africa/Kigali");
    private static final String CONTRIBUTION_WRITE = "CONTRIBUTION_WRITE";
    private static final String PAYOUT_WRITE = "PAYOUT_WRITE";

    private final CooperativeRepository cooperativeRepository;
    private final ContributionRepository contributionRepository;
    private final RotationEntitlementService entitlementService;
    private final RotationPayoutRepository payoutRepository;
    private final LedgerFinancialCalculationService financialCalculationService;
    private final MemberCreditService memberCreditService;
    private final rw.terimbere.csams.modules.rotation.pool.RotationPoolService rotationPoolService;
    private final CooperativeAuthorizationService authorizationService;
    private final Clock clock;

    public record Summary(
            UUID cooperativeId,
            String currency,
            SchemeOperatingModel operatingModel,
            /** Expectations: never counted as collected cash. */
            BigDecimal expectedContributions,
            /** Everything actually paid and posted on contribution obligations. */
            BigDecimal postedCollections,
            /** Unpaid on obligations whose configured due date has passed. */
            BigDecimal contributionArrears,
            BigDecimal outstandingContributions,
            /**
             * Collections that are member capital of the scheme: collections minus rotating funds (verified or not)
             * minus unclassified collections. The categories add up to postedCollections, none counted twice.
             */
            BigDecimal retainedCollections,
            /**
             * Paid money in a rotating or hybrid scheme whose character is genuinely unknown (no model snapshot, or a
             * hybrid split that was never recorded). Assigned to nobody: neither retained capital nor a recipient.
             */
            BigDecimal unclassifiedCollections,
            /**
             * Money known to be rotating whose original period cannot be established. Held for recipients (part of the
             * liabilities), excluded from retained capital, and never payout-eligible until it is attributed.
             */
            BigDecimal unresolvedRotatingCollections,
            /** Rotating funds whose receipt was verified: the base of recipient entitlements. */
            BigDecimal rotatingCollected,
            /** Rotating money paid through unverified paths: in the ledger as cash, owed to nobody until reconciled. */
            BigDecimal rotatingRequiringReconciliation,
            BigDecimal rotatingExpected,
            BigDecimal rotatingArrears,
            /** What recipients are still owed out of money that really arrived (a liability, not member capital). */
            BigDecimal recipientEntitlementsOwed,
            /** Owed on verified money plus unreconciled and unresolved rotating money: everything held for recipients. */
            BigDecimal totalRotatingLiabilities,
            BigDecimal payoutsDisbursed,
            BigDecimal payoutsOpen,
            BigDecimal availableFunds,
            /** Available funds after setting aside everything held for recipients (verified owed and unreconciled). */
            BigDecimal availableAfterEntitlements,
            /**
             * Money members paid in excess and the scheme holds for them (advance contributions, loan prepayments and
             * unapplied credit). Never income, never member capital, and not part of the available funds above.
             */
            BigDecimal memberCreditHeld,
            /**
             * The rotating pool (collected, opening, available, unused reserve) of a Rotating or Hybrid scheme; null for
             * an Accumulating one.
             */
            rw.terimbere.csams.modules.rotation.pool.RotationPoolService.SchemePool rotationPool) {}

    @Transactional(readOnly = true)
    public Summary summary(UUID cooperativeId) {
        authorizationService.requireMembership(cooperativeId);
        UserPrincipal actor = authorizationService.currentPrincipal();
        if (!actor.hasRole(CooperativeAuthorizationService.SUPER_ADMIN)
                && !actor.hasAuthority(CONTRIBUTION_WRITE)
                && !actor.hasAuthority(PAYOUT_WRITE)) {
            throw new ForbiddenException("The model financial summary is for officers");
        }
        Cooperative cooperative = cooperativeRepository
                .findByIdAndDeletedFalse(cooperativeId)
                .orElseThrow(() -> new ResourceNotFoundException("Cooperative", cooperativeId));
        LocalDate today = LocalDate.now(clock.withZone(KIGALI));

        BigDecimal expected = scale(contributionRepository.sumExpectedActive(cooperativeId));
        BigDecimal collected = scale(contributionRepository.sumPaidAll(cooperativeId));
        BigDecimal arrears = scale(contributionRepository.sumOverdueOutstanding(cooperativeId, today));
        BigDecimal rotatingCollected = entitlementService.totalCollectedRotating(cooperativeId);
        BigDecimal unreconciled = entitlementService.totalUnreconciledRotating(cooperativeId);
        List<PeriodEntitlement> periods = entitlementService.overview(cooperativeId);
        BigDecimal rotatingExpected = periods.stream().map(PeriodEntitlement::expectedRotating).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal rotatingArrears = periods.stream().map(PeriodEntitlement::rotatingArrears).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal owed = entitlementService.totalOutstandingEntitlement(cooperativeId);
        BigDecimal disbursed = scale(payoutRepository.sumAmountForScheme(cooperativeId, EnumSet.of(RotationPayoutStatus.DISBURSED)));
        BigDecimal open = scale(payoutRepository.sumAmountForScheme(
                cooperativeId, EnumSet.of(RotationPayoutStatus.REQUESTED, RotationPayoutStatus.APPROVED)));
        BigDecimal available = scale(financialCalculationService.calculateAvailableGroupFund(cooperativeId));
        var unresolved = entitlementService.unresolvedHistory(cooperativeId, cooperative.getOperatingModel());
        BigDecimal unclassified = scale(unresolved.unknown());
        BigDecimal unresolvedRotating = scale(unresolved.knownRotating());
        BigDecimal liabilities = owed.add(unreconciled).add(unresolvedRotating);

        return new Summary(
                cooperativeId,
                cooperative.getCurrency(),
                cooperative.getOperatingModel(),
                expected,
                collected,
                arrears,
                scale(expected.subtract(collected).max(BigDecimal.ZERO)),
                scale(collected
                        .subtract(rotatingCollected)
                        .subtract(unreconciled)
                        .subtract(unresolvedRotating)
                        .subtract(unclassified)),
                unclassified,
                unresolvedRotating,
                rotatingCollected,
                unreconciled,
                scale(rotatingExpected),
                scale(rotatingArrears),
                owed,
                scale(liabilities),
                disbursed,
                open,
                available,
                scale(available.subtract(liabilities)),
                scale(memberCreditService.totalHeld(cooperativeId)),
                cooperative.getOperatingModel() == rw.terimbere.csams.modules.cooperative.entity.SchemeOperatingModel.ACCUMULATING
                        ? null
                        : rotationPoolService.scheme(cooperativeId));
    }

    private static BigDecimal scale(BigDecimal value) {
        return MoneyUtils.scaleForStorage(value == null ? BigDecimal.ZERO : value);
    }
}
