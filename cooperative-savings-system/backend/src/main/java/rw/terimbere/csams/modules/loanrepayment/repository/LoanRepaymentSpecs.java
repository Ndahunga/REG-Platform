package rw.terimbere.csams.modules.loanrepayment.repository;

import jakarta.persistence.criteria.Predicate;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.domain.Specification;
import rw.terimbere.csams.modules.loanrepayment.entity.LoanRepayment;

/**
 * Optional filters only. JPQL {@code :fromDate IS NULL} binds an untyped LocalDate on PostgreSQL.
 */
public final class LoanRepaymentSpecs {

    private LoanRepaymentSpecs() {}

    public static Specification<LoanRepayment> filtered(
            UUID cooperativeId, UUID memberUserId, LocalDate fromDate, LocalDate toDate) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            predicates.add(cb.equal(root.get("cooperativeId"), cooperativeId));
            predicates.add(cb.isNull(root.get("reversedAt"))); // a reversed repayment is no longer a repayment
            if (memberUserId != null) {
                predicates.add(cb.equal(root.get("memberUserId"), memberUserId));
            }
            if (fromDate != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("paymentDate"), fromDate));
            }
            if (toDate != null) {
                predicates.add(cb.lessThanOrEqualTo(root.get("paymentDate"), toDate));
            }
            return cb.and(predicates.toArray(Predicate[]::new));
        };
    }
}
