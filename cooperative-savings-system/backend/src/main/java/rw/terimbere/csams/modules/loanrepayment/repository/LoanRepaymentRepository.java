package rw.terimbere.csams.modules.loanrepayment.repository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import rw.terimbere.csams.modules.loanrepayment.entity.LoanRepayment;

public interface LoanRepaymentRepository
        extends JpaRepository<LoanRepayment, UUID>, JpaSpecificationExecutor<LoanRepayment> {

    List<LoanRepayment> findByLoanIdAndCooperativeIdOrderByPaymentDateDescCreatedAtDesc(
            UUID loanId, UUID cooperativeId);

    List<LoanRepayment> findByLoanIdOrderByPaymentDateAscCreatedAtAsc(UUID loanId);

    List<LoanRepayment> findByCooperativeIdAndLoanIdAndPaymentDateAndAmountTotal(
            UUID cooperativeId, UUID loanId, LocalDate paymentDate, BigDecimal amountTotal);

    default List<LoanRepayment> findFiltered(
            UUID cooperativeId, UUID memberUserId, LocalDate fromDate, LocalDate toDate) {
        return findAll(
                LoanRepaymentSpecs.filtered(cooperativeId, memberUserId, fromDate, toDate),
                Sort.by(Sort.Direction.DESC, "paymentDate").and(Sort.by(Sort.Direction.DESC, "createdAt")));
    }

    @Query(
            """
            SELECT COALESCE(SUM(r.amountTotal), 0)
            FROM LoanRepayment r
            WHERE r.cooperativeId = :cooperativeId
              AND r.paymentDate >= :fromDate
              AND r.paymentDate <= :toDate
              AND r.reversedAt IS NULL
            """)
    BigDecimal sumAmountTotalInDateRange(
            @Param("cooperativeId") UUID cooperativeId,
            @Param("fromDate") LocalDate fromDate,
            @Param("toDate") LocalDate toDate);

    /** Principal repaid in OuWealth on loans imported by the opening-balance migration. */
    @Query(
            """
            SELECT COALESCE(SUM(r.principalPortion), 0)
            FROM LoanRepayment r, rw.terimbere.csams.modules.loan.entity.Loan l
            WHERE r.loanId = l.id
              AND l.cooperativeId = :cooperativeId
              AND l.openingImportId IS NOT NULL
              AND r.reversedAt IS NULL
            """)
    BigDecimal sumPrincipalRepaidOnImportedLoans(@Param("cooperativeId") UUID cooperativeId);

    /** A repayment of the same loan recorded after this one and still in force. */
    @Query(
            """
            SELECT COUNT(r) > 0
            FROM LoanRepayment r
            WHERE r.loanId = :loanId
              AND r.reversedAt IS NULL
              AND r.id <> :repaymentId
              AND r.createdAt > :after
            """)
    boolean existsLaterActiveRepayment(
            @Param("loanId") UUID loanId, @Param("repaymentId") UUID repaymentId, @Param("after") java.time.Instant after);

    /** True when the repayment was posted from a member payment (it is corrected by reversing that payment instead). */
    @Query(value = "SELECT COUNT(*) FROM member_payment_postings WHERE loan_repayment_id = :id", nativeQuery = true)
    long countMemberPaymentPostings(@Param("id") UUID id);

    /** True when the repayment came from a member credit applied to the loan. */
    @Query(value = "SELECT COUNT(*) FROM member_credit_entries WHERE applied_loan_repayment_id = :id", nativeQuery = true)
    long countMemberCreditApplications(@Param("id") UUID id);

    List<LoanRepayment> findByRevisionOfIdIn(java.util.Collection<UUID> ids);
}
