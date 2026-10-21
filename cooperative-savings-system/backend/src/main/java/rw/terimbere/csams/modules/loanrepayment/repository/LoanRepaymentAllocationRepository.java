package rw.terimbere.csams.modules.loanrepayment.repository;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import rw.terimbere.csams.modules.loanrepayment.entity.LoanRepaymentAllocation;

public interface LoanRepaymentAllocationRepository extends JpaRepository<LoanRepaymentAllocation, UUID> {

    List<LoanRepaymentAllocation> findByRepaymentIdOrderByCreatedAtAsc(UUID repaymentId);

    List<LoanRepaymentAllocation> findByLoanIdOrderByCreatedAtAsc(UUID loanId);
}
