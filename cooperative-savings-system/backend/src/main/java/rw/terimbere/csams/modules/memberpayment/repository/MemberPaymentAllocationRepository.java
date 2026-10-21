package rw.terimbere.csams.modules.memberpayment.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import rw.terimbere.csams.modules.memberpayment.entity.MemberPaymentAllocation;

public interface MemberPaymentAllocationRepository extends JpaRepository<MemberPaymentAllocation, UUID> {

    List<MemberPaymentAllocation> findByPaymentIdOrderByCreatedAtAscIdAsc(UUID paymentId);

    /** Batch lookup used to label list rows with their allocation types without one query per payment. */
    List<MemberPaymentAllocation> findByPaymentIdIn(Collection<UUID> paymentIds);

    Optional<MemberPaymentAllocation> findByIdAndPaymentIdAndCooperativeId(
            UUID id, UUID paymentId, UUID cooperativeId);
}
