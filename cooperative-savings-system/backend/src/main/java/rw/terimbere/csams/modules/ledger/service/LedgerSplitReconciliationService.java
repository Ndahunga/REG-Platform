package rw.terimbere.csams.modules.ledger.service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import rw.terimbere.csams.modules.contribution.entity.Contribution;
import rw.terimbere.csams.modules.contribution.repository.ContributionRepository;
import rw.terimbere.csams.modules.contribution.service.ContributionService;
import rw.terimbere.csams.modules.ledger.entity.LedgerEntry;
import rw.terimbere.csams.modules.ledger.repository.LedgerEntryRepository;
import rw.terimbere.csams.security.CooperativeAuthorizationService;
import rw.terimbere.csams.shared.utilities.MoneyUtils;

/**
 * Checks that the ledger keeps rotating and retained money apart without duplicating or losing cash.
 *
 * <p>Every contribution that has been paid must have exactly one live ledger credit equal to what was paid, and (for
 * entries written since the split was recorded) that credit's rotating and retained parts must equal the split of the
 * contribution itself. The parts always add up to the entry, so the two totals can never add cash. Entries written
 * before the split existed are reported as unclassified, not guessed at.
 */
@Service
@RequiredArgsConstructor
public class LedgerSplitReconciliationService {

    private final LedgerEntryRepository ledgerEntryRepository;
    private final ContributionRepository contributionRepository;
    private final CooperativeAuthorizationService authorizationService;

    public record Mismatch(UUID contributionId, String reason) {}

    public record Reconciliation(
            /** Cash credited to the ledger for contributions that is classified as rotating. */
            BigDecimal ledgerRotating,
            /** Cash credited to the ledger for contributions that is classified as retained (member capital). */
            BigDecimal ledgerRetained,
            /** Contribution cash credited before the split existed: neither rotating nor retained until reconciled. */
            BigDecimal ledgerUnclassified,
            BigDecimal ledgerTotal,
            BigDecimal contributionsPaid,
            /** Ledger total less what the contributions say was paid: zero means no cash is duplicated or missing. */
            BigDecimal cashDifference,
            BigDecimal derivedRotating,
            BigDecimal derivedRetained,
            int unclassifiedEntries,
            List<Mismatch> mismatches,
            boolean balanced) {}

    @Transactional(readOnly = true)
    public Reconciliation reconcile(UUID cooperativeId) {
        authorizationService.requireMembership(cooperativeId);
        Map<UUID, LedgerEntry> liveBySource = new HashMap<>();
        List<Mismatch> mismatches = new ArrayList<>();
        BigDecimal rotating = BigDecimal.ZERO;
        BigDecimal retained = BigDecimal.ZERO;
        BigDecimal unclassified = BigDecimal.ZERO;
        BigDecimal ledgerTotal = BigDecimal.ZERO;
        int unclassifiedEntries = 0;
        for (LedgerEntry entry : ledgerEntryRepository.findLiveContributionCash(cooperativeId)) {
            if (liveBySource.put(entry.getSourceEntityId(), entry) != null) {
                mismatches.add(new Mismatch(entry.getSourceEntityId(), "MORE_THAN_ONE_LIVE_ENTRY"));
            }
            ledgerTotal = ledgerTotal.add(entry.getCreditAmount());
            if (entry.getRotatingAmount() == null) {
                unclassified = unclassified.add(entry.getCreditAmount());
                unclassifiedEntries++;
            } else {
                rotating = rotating.add(entry.getRotatingAmount());
                retained = retained.add(entry.getRetainedAmount());
            }
        }

        BigDecimal paidTotal = BigDecimal.ZERO;
        BigDecimal derivedRotating = BigDecimal.ZERO;
        BigDecimal derivedRetained = BigDecimal.ZERO;
        for (Contribution contribution : contributionRepository.findPaid(cooperativeId)) {
            paidTotal = paidTotal.add(contribution.getPaidAmount());
            var split = ContributionService.ledgerSplit(contribution);
            if (split != null) {
                derivedRotating = derivedRotating.add(split.rotating());
                derivedRetained = derivedRetained.add(split.retained());
            }
            LedgerEntry live = liveBySource.remove(contribution.getId());
            if (live == null) {
                mismatches.add(new Mismatch(contribution.getId(), "NO_LIVE_LEDGER_ENTRY"));
                continue;
            }
            if (MoneyUtils.scaleForStorage(live.getCreditAmount()).compareTo(MoneyUtils.scaleForStorage(contribution.getPaidAmount())) != 0) {
                mismatches.add(new Mismatch(contribution.getId(), "LEDGER_AMOUNT_DIFFERS_FROM_PAID"));
            } else if (live.getRotatingAmount() != null && split != null
                    && (MoneyUtils.scaleForStorage(live.getRotatingAmount()).compareTo(MoneyUtils.scaleForStorage(split.rotating())) != 0
                            || MoneyUtils.scaleForStorage(live.getRetainedAmount()).compareTo(MoneyUtils.scaleForStorage(split.retained())) != 0)) {
                mismatches.add(new Mismatch(contribution.getId(), "SPLIT_DIFFERS_FROM_CONTRIBUTION"));
            }
        }
        // a live ledger credit whose contribution has nothing paid any more is cash with no obligation behind it
        liveBySource.keySet().forEach(id -> mismatches.add(new Mismatch(id, "LEDGER_ENTRY_WITHOUT_PAID_CONTRIBUTION")));

        BigDecimal cashDifference = MoneyUtils.scaleForStorage(ledgerTotal.subtract(paidTotal));
        return new Reconciliation(
                MoneyUtils.scaleForStorage(rotating),
                MoneyUtils.scaleForStorage(retained),
                MoneyUtils.scaleForStorage(unclassified),
                MoneyUtils.scaleForStorage(ledgerTotal),
                MoneyUtils.scaleForStorage(paidTotal),
                cashDifference,
                MoneyUtils.scaleForStorage(derivedRotating),
                MoneyUtils.scaleForStorage(derivedRetained),
                unclassifiedEntries,
                mismatches,
                cashDifference.signum() == 0 && mismatches.isEmpty());
    }
}
