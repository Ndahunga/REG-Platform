package rw.terimbere.csams.modules.membercredit;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.Immutable;
import rw.terimbere.csams.shared.common.entity.BaseEntity;

/**
 * One line of the journal of a member's credit in one Saving Scheme. Append-only: entries are never edited or deleted, a
 * correction is a new entry. The balance of a member is always computed from these lines, never stored. Every entry has
 * its own ledger entry, so the journal and the ledger can be reconciled.
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Immutable
@Entity
@Table(name = "member_credit_entries")
public class MemberCreditEntry extends BaseEntity {

    @Column(name = "cooperative_id", nullable = false, updatable = false)
    private UUID cooperativeId;

    @Column(name = "member_user_id", nullable = false, updatable = false)
    private UUID memberUserId;

    @Enumerated(EnumType.STRING)
    @Column(name = "entry_type", nullable = false, updatable = false, length = 16)
    private CreditEntryType entryType;

    @Enumerated(EnumType.STRING)
    @Column(name = "purpose", nullable = false, updatable = false, length = 24)
    private CreditPurpose purpose;

    /** The loan of a LOAN_PREPAYMENT credit; null for every other purpose. */
    @Column(name = "loan_id", updatable = false)
    private UUID loanId;

    @Column(name = "amount", nullable = false, updatable = false, precision = 19, scale = 4)
    private BigDecimal amount;

    @Column(name = "source_payment_id", updatable = false)
    private UUID sourcePaymentId;

    @Column(name = "source_allocation_id", updatable = false)
    private UUID sourceAllocationId;

    @Column(name = "applied_contribution_id", updatable = false)
    private UUID appliedContributionId;

    @Column(name = "applied_loan_repayment_id", updatable = false)
    private UUID appliedLoanRepaymentId;

    @Column(name = "reverses_entry_id", updatable = false)
    private UUID reversesEntryId;

    @Column(name = "ledger_entry_id", nullable = false, updatable = false)
    private UUID ledgerEntryId;

    @Column(name = "idempotency_key", updatable = false, length = 128)
    private String idempotencyKey;

    @Column(name = "note", updatable = false, length = 500)
    private String note;

    @Column(name = "created_by", nullable = false, updatable = false)
    private UUID createdBy;
}
