package rw.terimbere.csams.modules.ledger.repository;

import jakarta.persistence.criteria.Predicate;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.domain.Specification;
import rw.terimbere.csams.modules.ledger.entity.LedgerEntry;
import rw.terimbere.csams.shared.financial.LedgerTransactionType;

/**
 * Optional filters only. JPQL {@code :fromDate IS NULL} binds an untyped LocalDate on PostgreSQL.
 */
public final class LedgerEntrySpecs {

    private LedgerEntrySpecs() {}

    public static Specification<LedgerEntry> filtered(
            UUID cooperativeId,
            LedgerTransactionType transactionType,
            LocalDate fromDate,
            LocalDate toDate,
            UUID memberUserId,
            String sourceEntityType) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            predicates.add(cb.equal(root.get("cooperativeId"), cooperativeId));
            if (transactionType != null) {
                predicates.add(cb.equal(root.get("transactionType"), transactionType));
            }
            if (fromDate != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("transactionDate"), fromDate));
            }
            if (toDate != null) {
                predicates.add(cb.lessThanOrEqualTo(root.get("transactionDate"), toDate));
            }
            if (memberUserId != null) {
                predicates.add(cb.equal(root.get("memberUserId"), memberUserId));
            }
            if (sourceEntityType != null) {
                predicates.add(cb.equal(root.get("sourceEntityType"), sourceEntityType));
            }
            return cb.and(predicates.toArray(Predicate[]::new));
        };
    }
}
