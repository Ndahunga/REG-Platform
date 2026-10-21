package rw.terimbere.csams.modules.incomeexpense.repository;

import jakarta.persistence.criteria.Predicate;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.domain.Specification;
import rw.terimbere.csams.modules.incomeexpense.entity.IncomeExpenseApprovalStatus;
import rw.terimbere.csams.modules.incomeexpense.entity.IncomeExpenseCategory;
import rw.terimbere.csams.modules.incomeexpense.entity.IncomeExpenseTransaction;

/**
 * Optional filters only. JPQL {@code :fromDate IS NULL} binds an untyped LocalDate on PostgreSQL.
 */
public final class IncomeExpenseSpecs {

    private IncomeExpenseSpecs() {}

    public static Specification<IncomeExpenseTransaction> filtered(
            UUID cooperativeId,
            IncomeExpenseCategory category,
            IncomeExpenseApprovalStatus status,
            LocalDate fromDate,
            LocalDate toDate) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            predicates.add(cb.equal(root.get("cooperativeId"), cooperativeId));
            if (category != null) {
                predicates.add(cb.equal(root.get("category"), category));
            }
            if (status != null) {
                predicates.add(cb.equal(root.get("approvalStatus"), status));
            }
            if (fromDate != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("transactionDate"), fromDate));
            }
            if (toDate != null) {
                predicates.add(cb.lessThanOrEqualTo(root.get("transactionDate"), toDate));
            }
            return cb.and(predicates.toArray(Predicate[]::new));
        };
    }
}
