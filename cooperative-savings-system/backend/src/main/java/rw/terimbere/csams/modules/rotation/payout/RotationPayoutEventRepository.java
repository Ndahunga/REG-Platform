package rw.terimbere.csams.modules.rotation.payout;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RotationPayoutEventRepository extends JpaRepository<RotationPayoutEvent, UUID> {

    List<RotationPayoutEvent> findByCooperativeIdAndPayoutIdOrderByCreatedAtAsc(UUID cooperativeId, UUID payoutId);
}
