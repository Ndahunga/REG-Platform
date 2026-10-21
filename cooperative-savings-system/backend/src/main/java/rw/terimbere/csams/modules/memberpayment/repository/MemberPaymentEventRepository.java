package rw.terimbere.csams.modules.memberpayment.repository;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import rw.terimbere.csams.modules.memberpayment.entity.MemberPaymentEvent;

public interface MemberPaymentEventRepository extends JpaRepository<MemberPaymentEvent, UUID> {

    List<MemberPaymentEvent> findByCooperativeIdAndPaymentIdOrderByCreatedAtAsc(UUID cooperativeId, UUID paymentId);
}
