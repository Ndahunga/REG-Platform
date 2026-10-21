package rw.terimbere.csams.modules.contribution.repository;

import jakarta.persistence.criteria.Predicate;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.domain.Specification;
import rw.terimbere.csams.modules.contribution.entity.Contribution;
import rw.terimbere.csams.modules.contribution.entity.ContributionStatus;

/**
 * Optional contribution filters. Hibernate expands JPQL {@code (:param IS NULL OR column ? :param)}
 * into two JDBC binds per named parameter. The first bind is a bare {@code ? IS NULL} with no
 * column to infer a type from. PostgreSQL then fails with
 * {@code could not determine data type of parameter $10} — that ordinal is {@code :fromDate}
 * ({@link LocalDate} / SQL {@code date}) in {@code ContributionRepository.search}.
 *
 * <p>Applying predicates only when the value is present never sends that untyped {@code IS NULL}
 * bind. Same approach as {@code AuditLogSpecs}.
 */
public final class ContributionSpecs {

    private ContributionSpecs() {}

    public static Specification<Contribution> filtered(
            UUID cooperativeId,
            UUID memberUserId,
            Integer year,
            Integer month,
            ContributionStatus status,
            LocalDate fromDate,
            LocalDate toDate) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            predicates.add(cb.equal(root.get("cooperativeId"), cooperativeId));
            if (memberUserId != null) {
                predicates.add(cb.equal(root.get("memberUserId"), memberUserId));
            }
            if (year != null) {
                predicates.add(cb.equal(root.get("year"), year));
            }
            if (month != null) {
                predicates.add(cb.equal(root.get("month"), month));
            }
            if (status != null) {
                predicates.add(cb.equal(root.get("status"), status));
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
