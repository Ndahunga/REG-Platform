package rw.terimbere.csams.modules.rotation.payout;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RotationPayoutOffsetRepository extends JpaRepository<RotationPayoutOffset, UUID> {

    List<RotationPayoutOffset> findByCooperativeId(UUID cooperativeId);

    List<RotationPayoutOffset> findByCooperativeIdAndRotationPeriodId(UUID cooperativeId, UUID periodId);

    List<RotationPayoutOffset> findByPayoutId(UUID payoutId);
}
