package rw.terimbere.csams.modules.memberpayment.repository;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import rw.terimbere.csams.modules.memberpayment.entity.MemberPaymentLoanReversal;

/** Append-only, like the loan repayments it compensates. */
public interface MemberPaymentLoanReversalRepository extends JpaRepository<MemberPaymentLoanReversal, UUID> {

    List<MemberPaymentLoanReversal> findByCooperativeIdAndPaymentId(UUID cooperativeId, UUID paymentId);

    boolean existsByPostingId(UUID postingId);
}
