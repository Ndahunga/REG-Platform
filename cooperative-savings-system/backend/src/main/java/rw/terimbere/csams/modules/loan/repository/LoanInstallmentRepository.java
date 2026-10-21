package rw.terimbere.csams.modules.loan.repository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.time.LocalDate;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import rw.terimbere.csams.modules.loan.entity.LoanInstallment;
import rw.terimbere.csams.modules.loan.entity.LoanInstallmentStatus;

public interface LoanInstallmentRepository extends JpaRepository<LoanInstallment, UUID> {

    List<LoanInstallment> findByLoanIdOrderByInstallmentNumberAsc(UUID loanId);

    boolean existsByLoanId(UUID loanId);

    List<LoanInstallment> findByStatusInAndDueDateLessThanEqual(
            Collection<LoanInstallmentStatus> statuses, LocalDate asOf);

    /**
     * Compact installment facts for repayment-reliability analytics (read-only).
     *
     * <p>Columns: memberUserId, installmentId, dueDate, status, principalDue, interestDue,
     * penaltyDue, principalPaid, interestPaid, penaltyPaid, completedOn ({@code MAX} paymentDate).
     */
    @Query(
            """
            SELECT l.memberUserId,
                   i.id,
                   i.dueDate,
                   i.status,
                   i.principalDue,
                   i.interestDue,
                   i.penaltyDue,
                   i.principalPaid,
                   i.interestPaid,
                   i.penaltyPaid,
                   MAX(r.paymentDate)
            FROM LoanInstallment i
            JOIN Loan l ON l.id = i.loanId
            LEFT JOIN LoanRepaymentAllocation a ON a.installmentId = i.id
            LEFT JOIN LoanRepayment r ON r.id = a.repaymentId AND r.reversedAt IS NULL
            WHERE i.cooperativeId = :cooperativeId
            GROUP BY l.memberUserId,
                     i.id,
                     i.dueDate,
                     i.status,
                     i.principalDue,
                     i.interestDue,
                     i.penaltyDue,
                     i.principalPaid,
                     i.interestPaid,
                     i.penaltyPaid
            """)
    List<Object[]> findRepaymentReliabilityFacts(@Param("cooperativeId") UUID cooperativeId);
}
