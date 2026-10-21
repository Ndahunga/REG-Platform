package rw.terimbere.csams.modules.loan.repository;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import rw.terimbere.csams.modules.loan.entity.LoanShareTier;

public interface LoanShareTierRepository extends JpaRepository<LoanShareTier, UUID> {

    List<LoanShareTier> findByCooperativeIdOrderByMinSharePercentDesc(UUID cooperativeId);

    void deleteByCooperativeId(UUID cooperativeId);
}
