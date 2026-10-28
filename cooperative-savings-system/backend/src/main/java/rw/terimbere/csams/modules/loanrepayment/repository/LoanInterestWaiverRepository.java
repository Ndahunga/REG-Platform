package rw.terimbere.csams.modules.loanrepayment.repository;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import rw.terimbere.csams.modules.loanrepayment.entity.LoanInterestWaiver;

public interface LoanInterestWaiverRepository extends JpaRepository<LoanInterestWaiver, UUID> {

    List<LoanInterestWaiver> findByRepaymentId(UUID repaymentId);
}
