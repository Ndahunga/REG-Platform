package rw.terimbere.csams.modules.loan.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import rw.terimbere.csams.modules.loan.entity.LoanGuarantor;
import rw.terimbere.csams.modules.loan.entity.LoanGuarantorStatus;

public interface LoanGuarantorRepository extends JpaRepository<LoanGuarantor, UUID> {

    Optional<LoanGuarantor> findByLoanId(UUID loanId);

    Optional<LoanGuarantor> findByIdAndCooperativeId(UUID id, UUID cooperativeId);

    List<LoanGuarantor> findByCooperativeIdAndGuarantorUserIdAndStatusOrderByRequestedAtDesc(
            UUID cooperativeId, UUID guarantorUserId, LoanGuarantorStatus status);

    List<LoanGuarantor> findByCooperativeIdAndGuarantorUserIdOrderByRequestedAtDesc(
            UUID cooperativeId, UUID guarantorUserId);
}
