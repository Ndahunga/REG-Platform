package rw.terimbere.csams.modules.memberpayment.repository;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import rw.terimbere.csams.modules.memberpayment.entity.MemberPaymentPosting;

/** Append-only: postings are inserted once and never updated or deleted. */
public interface MemberPaymentPostingRepository extends JpaRepository<MemberPaymentPosting, UUID> {

    List<MemberPaymentPosting> findByCooperativeIdAndPaymentIdOrderByPostedAtAscIdAsc(UUID cooperativeId, UUID paymentId);

    boolean existsByPaymentId(UUID paymentId);

    /** Every posting of the scheme attributed to a rotation period. */
    List<MemberPaymentPosting> findByCooperativeIdAndRotationPeriodIdIsNotNull(UUID cooperativeId);

    /** Postings attributed to one rotation period, oldest first (the late-recovery history of its recipient). */
    List<MemberPaymentPosting> findByCooperativeIdAndRotationPeriodIdOrderByPostedAtAscIdAsc(
            UUID cooperativeId, UUID rotationPeriodId);
}
