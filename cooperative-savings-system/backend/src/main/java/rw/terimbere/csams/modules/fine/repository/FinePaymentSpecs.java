package rw.terimbere.csams.modules.fine.repository;

import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.data.jpa.domain.Specification;
import rw.terimbere.csams.modules.fine.entity.FinePayment;
import rw.terimbere.csams.modules.fine.entity.FinePaymentStatus;
import rw.terimbere.csams.modules.user.entity.User;

/**
 * Optional filters only. JPQL {@code :fromDate IS NULL} binds an untyped LocalDate on PostgreSQL.
 */
public final class FinePaymentSpecs {

    private FinePaymentSpecs() {}

    public static Specification<FinePayment> filtered(
            UUID cooperativeId,
            UUID memberUserId,
            FinePaymentStatus status,
            LocalDate fromDate,
            LocalDate toDate) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            predicates.add(cb.equal(root.get("cooperativeId"), cooperativeId));
            if (memberUserId != null) {
                predicates.add(cb.equal(root.get("memberUserId"), memberUserId));
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

    /** Payment queue: same optional filters plus optional member/reference search. */
    public static Specification<FinePayment> queue(
            UUID cooperativeId,
            FinePaymentStatus status,
            LocalDate fromDate,
            LocalDate toDate,
            String q) {
        Specification<FinePayment> spec = filtered(cooperativeId, null, status, fromDate, toDate);
        if (q == null || q.isBlank()) {
            return spec;
        }
        return spec.and(matchesSearch(q.trim()));
    }

    private static Specification<FinePayment> matchesSearch(String q) {
        String like = "%" + q.toLowerCase(Locale.ROOT) + "%";
        return (root, query, cb) -> {
            Predicate referenceMatch =
                    cb.like(cb.lower(cb.coalesce(root.get("paymentReference"), "")), like);
            if (query == null) {
                return referenceMatch;
            }
            Subquery<Integer> memberMatch = query.subquery(Integer.class);
            Root<User> user = memberMatch.from(User.class);
            memberMatch.select(cb.literal(1));
            Predicate fullName = cb.like(
                    cb.lower(cb.concat(
                            cb.concat(cb.coalesce(user.get("firstName"), ""), " "),
                            cb.coalesce(user.get("lastName"), ""))),
                    like);
            memberMatch.where(
                    cb.equal(user.get("id"), root.get("memberUserId")),
                    cb.or(
                            cb.like(cb.lower(cb.coalesce(user.get("firstName"), "")), like),
                            cb.like(cb.lower(cb.coalesce(user.get("lastName"), "")), like),
                            cb.like(cb.lower(cb.coalesce(user.get("username"), "")), like),
                            fullName));
            return cb.or(referenceMatch, cb.exists(memberMatch));
        };
    }
}
