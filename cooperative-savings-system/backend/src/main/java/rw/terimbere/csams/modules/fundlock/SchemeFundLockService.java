package rw.terimbere.csams.modules.fundlock;

import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Serializes the operations that spend a scheme's funds.
 *
 * <p>Every protected operation calls {@link #lockSchemeFund} first, before it reads the target entity for its final
 * decision and before it reads available funds. The lock is a row lock on that scheme's {@code scheme_fund_locks}
 * row, taken in the caller's own transaction and released by its commit or rollback; two protected operations of the
 * same scheme therefore run one after the other, each seeing the other's committed postings, while operations of
 * different schemes never wait for each other.
 *
 * <p>Protected today: loan disbursement, payout confirmation and investment activation. Other paths that reduce funds
 * (expense approval, contribution corrections, social fund disbursements) do not take this lock, so it does not by
 * itself guarantee that available funds can never become negative.
 */
@Service
@RequiredArgsConstructor
public class SchemeFundLockService {

    private final SchemeFundLockRepository repository;

    /**
     * Acquires the scheme's fund lock. Must be called inside an active transaction (it never opens its own, so the
     * lock lives exactly as long as the financial transaction). Fails, rather than continuing unlocked, if the scheme
     * has no lock row. Never creates one on demand: that would be an unsafe check-then-insert.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void lockSchemeFund(UUID cooperativeId) {
        repository
                .findForUpdate(cooperativeId)
                .orElseThrow(() -> new IllegalStateException("Scheme fund lock is missing for scheme " + cooperativeId));
    }

    /** Creates the lock row for a scheme that was just created, in the same transaction as the scheme. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void initializeForNewScheme(UUID cooperativeId) {
        repository.insertForNewScheme(cooperativeId);
    }
}
