package rw.terimbere.csams.modules.historicalimport.strategy;

import java.time.LocalDate;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import rw.terimbere.csams.modules.historicalimport.entity.HistoricalImportStatus;
import rw.terimbere.csams.modules.historicalimport.repository.HistoricalImportRepository;
import rw.terimbere.csams.modules.historicalimport.transactions.HistoricalBatchStatus;
import rw.terimbere.csams.modules.historicalimport.transactions.HistoricalLedgerImportRepository;
import rw.terimbere.csams.shared.exceptions.ConflictException;

/**
 * Keeps a Saving Scheme from bringing its past in twice. A scheme has one migration strategy: OPENING_BALANCE (balances
 * carried in at a cutover date) or DETAILED_TRANSACTION_HISTORY (transactions posted to the ledger). Importing the same
 * history both ways would count every contribution, loan and balance twice, so the importer that does not match the
 * scheme's strategy is refused, and the strategy cannot be changed once anything has been brought in.
 *
 * <p>The strategy is chosen explicitly by an officer or, if none was chosen, set by the first import that is confirmed
 * (and recorded as such). One row per scheme (a UNIQUE constraint) makes two racing first imports collide rather than
 * both succeed.
 */
@Service
@RequiredArgsConstructor
public class HistoricalMigrationGuard {

    private final MigrationStrategyRepository repository;
    private final HistoricalImportRepository openingImportRepository;
    private final HistoricalLedgerImportRepository ledgerImportRepository;
    private final rw.terimbere.csams.modules.audit.service.AuditService auditService;

    @Transactional(readOnly = true)
    public MigrationStrategyRecord current(UUID cooperativeId) {
        return repository.findByCooperativeId(cooperativeId).orElse(null);
    }

    /** True when something has already been carried in under either strategy. */
    @Transactional(readOnly = true)
    public boolean anythingImported(UUID cooperativeId) {
        return openingImportRepository.existsByCooperativeIdAndStatusAndCutoverDateIsNotNull(
                        cooperativeId, HistoricalImportStatus.CONFIRMED)
                || ledgerImportRepository.existsByCooperativeIdAndStatus(cooperativeId, HistoricalBatchStatus.POSTED);
    }

    /** An officer chooses (or re-chooses, while nothing has been imported) the scheme's strategy. */
    @Transactional
    public MigrationStrategyRecord choose(UUID cooperativeId, MigrationStrategy strategy, LocalDate cutoverDate, UUID actor) {
        if (strategy == MigrationStrategy.OPENING_BALANCE && cutoverDate == null) {
            throw new ConflictException("MIGRATION_CUTOVER_REQUIRED", "An opening-balance migration needs a cutover date");
        }
        MigrationStrategyRecord existing = repository.findByCooperativeIdForUpdate(cooperativeId).orElse(null);
        if (existing != null) {
            requireNotUnderReview(existing);
            if (anythingImported(cooperativeId)
                    && (existing.getStrategy() != strategy
                            || (strategy == MigrationStrategy.OPENING_BALANCE
                                    && existing.getCutoverDate() != null
                                    && !existing.getCutoverDate().equals(cutoverDate)))) {
                throw new ConflictException(
                        "MIGRATION_STRATEGY_LOCKED",
                        "History was already brought in using " + existing.getStrategy() + ". The strategy can no longer be changed.");
            }
            existing.setStrategy(strategy);
            existing.setCutoverDate(strategy == MigrationStrategy.OPENING_BALANCE ? cutoverDate : null);
            existing.setSource("EXPLICIT");
            existing.setSetBy(actor);
            MigrationStrategyRecord saved = repository.saveAndFlush(existing);
            auditChosen(cooperativeId, saved, actor);
            return saved;
        }
        MigrationStrategyRecord created = create(cooperativeId, strategy, cutoverDate, "EXPLICIT", actor);
        auditChosen(cooperativeId, created, actor);
        return created;
    }

    /**
     * A person with authority attests that a scheme flagged for review has been reconciled and says which strategy is the
     * authoritative one. Nothing is guessed: the strategy, the reason and who decided are recorded, and the flag is only
     * cleared by this call. Reconciling the data itself (for example reversing the import that duplicated money) is done
     * beforehand through the normal correction workflows; this records the decision and unblocks further imports.
     */
    @Transactional
    public MigrationStrategyRecord resolveReview(
            UUID cooperativeId, MigrationStrategy strategy, LocalDate cutoverDate, String note, UUID actor) {
        String clean = note == null ? "" : note.trim();
        if (strategy == null) {
            throw new ConflictException("MIGRATION_STRATEGY_REQUIRED", "Choose the strategy that is authoritative for this scheme");
        }
        if (clean.length() < 20) {
            throw new ConflictException("MIGRATION_RESOLUTION_NOTE_REQUIRED", "Explain how the scheme was reconciled (at least 20 characters)");
        }
        if (strategy == MigrationStrategy.OPENING_BALANCE && cutoverDate == null) {
            throw new ConflictException("MIGRATION_CUTOVER_REQUIRED", "An opening-balance migration needs a cutover date");
        }
        MigrationStrategyRecord existing = repository.findByCooperativeIdForUpdate(cooperativeId).orElse(null);
        if (existing == null || !existing.isNeedsReview()) {
            throw new ConflictException("MIGRATION_NOT_UNDER_REVIEW", "This scheme has no migration review to resolve");
        }
        existing.setStrategy(strategy);
        existing.setCutoverDate(strategy == MigrationStrategy.OPENING_BALANCE ? cutoverDate : null);
        existing.setSource("EXPLICIT");
        existing.setSetBy(actor);
        existing.setNeedsReview(false);
        existing.setResolvedBy(actor);
        existing.setResolvedAt(java.time.Instant.now());
        existing.setResolutionNote(clean.length() > 1000 ? clean.substring(0, 1000) : clean);
        MigrationStrategyRecord saved = repository.saveAndFlush(existing);
        // Only enum values go into the audit payload; the free-text note stays on the record itself.
        auditService.record(
                actor,
                cooperativeId,
                rw.terimbere.csams.shared.auditing.AuditableAction.MIGRATION_STRATEGY_RESOLVED,
                "MigrationStrategy",
                saved.getId(),
                "{\"needsReview\":true}",
                "{\"needsReview\":false,\"strategy\":\"" + saved.getStrategy() + "\"}",
                null,
                null);
        return saved;
    }

    /** Every scheme still flagged for review (platform administrators). */
    @Transactional(readOnly = true)
    public java.util.List<MigrationStrategyRecord> underReview() {
        return repository.findByNeedsReviewTrueOrderByCreatedAtAsc();
    }

    private void auditChosen(UUID cooperativeId, MigrationStrategyRecord saved, UUID actor) {
        auditService.record(
                actor,
                cooperativeId,
                rw.terimbere.csams.shared.auditing.AuditableAction.MIGRATION_STRATEGY_CHOSEN,
                "MigrationStrategy",
                saved.getId(),
                null,
                "{\"strategy\":\"" + saved.getStrategy() + "\"}",
                null,
                null);
    }

    /**
     * Called by an importer before it brings anything in. Passes when the scheme's strategy is the importer's, sets the
     * strategy when none was chosen, and refuses otherwise.
     */
    @Transactional
    public void requireStrategy(UUID cooperativeId, MigrationStrategy needed, LocalDate cutoverDate, UUID actor) {
        MigrationStrategyRecord existing = repository.findByCooperativeIdForUpdate(cooperativeId).orElse(null);
        if (existing == null) {
            create(cooperativeId, needed, needed == MigrationStrategy.OPENING_BALANCE ? cutoverDate : null, "FIRST_IMPORT", actor);
            return;
        }
        requireNotUnderReview(existing);
        if (existing.getStrategy() != needed) {
            throw new ConflictException(
                    "MIGRATION_STRATEGY_CONFLICT",
                    needed == MigrationStrategy.OPENING_BALANCE
                            ? "This scheme migrates by detailed transaction history. Importing opening balances as well would count the same money twice."
                            : "This scheme migrates by opening balances. Importing the transaction history as well would count the same money twice.");
        }
        if (needed == MigrationStrategy.OPENING_BALANCE
                && existing.getCutoverDate() != null
                && cutoverDate != null
                && !existing.getCutoverDate().equals(cutoverDate)
                && anythingImported(cooperativeId)) {
            throw new ConflictException(
                    "MIGRATION_CUTOVER_MISMATCH",
                    "This scheme's opening balances are as at " + existing.getCutoverDate()
                            + ". Use the same cutover date or ask for the strategy to be reviewed.");
        }
    }

    private MigrationStrategyRecord create(UUID cooperativeId, MigrationStrategy strategy, LocalDate cutover, String source, UUID actor) {
        try {
            return repository.saveAndFlush(MigrationStrategyRecord.builder()
                    .cooperativeId(cooperativeId)
                    .strategy(strategy)
                    .cutoverDate(cutover)
                    .source(source)
                    .needsReview(false)
                    .setBy(actor)
                    .build());
        } catch (DataIntegrityViolationException e) {
            throw new ConflictException("MIGRATION_STRATEGY_CONFLICT", "Another migration was started for this scheme at the same time. Try again.");
        }
    }

    private void requireNotUnderReview(MigrationStrategyRecord record) {
        if (record.isNeedsReview()) {
            throw new ConflictException(
                    "MIGRATION_NEEDS_REVIEW",
                    "This scheme brought its history in both ways before the safeguard existed. Reconcile it with support before importing anything more.");
        }
    }
}
