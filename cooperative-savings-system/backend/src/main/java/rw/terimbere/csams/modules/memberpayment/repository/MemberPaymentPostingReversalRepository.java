package rw.terimbere.csams.modules.memberpayment.repository;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import rw.terimbere.csams.modules.memberpayment.entity.MemberPaymentPostingReversal;

/** Append-only, like the postings it compensates. */
public interface MemberPaymentPostingReversalRepository extends JpaRepository<MemberPaymentPostingReversal, UUID> {

    List<MemberPaymentPostingReversal> findByCooperativeIdAndPaymentId(UUID cooperativeId, UUID paymentId);

    List<MemberPaymentPostingReversal> findByCooperativeIdAndRotationPeriodId(UUID cooperativeId, UUID rotationPeriodId);

    boolean existsByPostingId(UUID postingId);

    List<MemberPaymentPostingReversal> findByCooperativeIdAndRotationPeriodIdIsNotNull(UUID cooperativeId);
}
