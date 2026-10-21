package rw.terimbere.csams.modules.memberpayment.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.Data;
import lombok.NoArgsConstructor;
import rw.terimbere.csams.modules.memberpayment.entity.MemberPaymentPostingReversal;
import rw.terimbere.csams.modules.memberpayment.entity.MemberPaymentReversal;
import rw.terimbere.csams.modules.memberpayment.entity.PaymentReversalStatus;

/** Request and response shapes of the contribution-only payment reversal workflow. */
public final class PaymentReversalDtos {

    private PaymentReversalDtos() {}

    /** Optional note for a withdrawal. */
    @Data
    @NoArgsConstructor
    public static class WithdrawRequest {
        @Size(max = 2000)
        private String note;

        @JsonAnySetter
        void rejectUnknownField(String name, Object value) {
            throw new IllegalArgumentException("Unknown field: " + name);
        }
    }

    @Data
    @NoArgsConstructor
    public static class ReasonRequest {
        @NotBlank
        @Size(max = 2000)
        private String reason;

        @JsonAnySetter
        void rejectUnknownField(String name, Object value) {
            throw new IllegalArgumentException("Unknown field: " + name);
        }
    }

    /** What a reversal took back from one posted allocation. */
    public record TakenBack(
            UUID postingId,
            UUID contributionId,
            int periodYear,
            int periodMonth,
            BigDecimal amount,
            BigDecimal paidBefore,
            BigDecimal paidAfter,
            BigDecimal rotatingAmount,
            BigDecimal retainedAmount,
            UUID rotationPeriodId,
            UUID originalRecipientUserId,
            UUID ledgerEntryId) {

        static TakenBack from(MemberPaymentPostingReversal r) {
            return new TakenBack(
                    r.getPostingId(),
                    r.getContributionId(),
                    r.getPeriodYear(),
                    r.getPeriodMonth(),
                    r.getAmount(),
                    r.getPaidBefore(),
                    r.getPaidAfter(),
                    r.getRotatingAmount(),
                    r.getRetainedAmount(),
                    r.getRotationPeriodId(),
                    r.getOriginalRecipientUserId(),
                    r.getLedgerEntryId());
        }
    }

    /** What a reversal gave back to one loan. */
    public record LoanGivenBack(
            UUID postingId,
            UUID loanId,
            UUID loanRepaymentId,
            BigDecimal principalAmount,
            BigDecimal interestAmount,
            BigDecimal penaltyAmount,
            BigDecimal outstandingPrincipalBefore,
            BigDecimal outstandingPrincipalAfter,
            BigDecimal outstandingInterestBefore,
            BigDecimal outstandingInterestAfter,
            String loanStatusBefore,
            String loanStatusAfter) {

        static LoanGivenBack from(rw.terimbere.csams.modules.memberpayment.entity.MemberPaymentLoanReversal r) {
            return new LoanGivenBack(
                    r.getPostingId(), r.getLoanId(), r.getLoanRepaymentId(), r.getPrincipalAmount(), r.getInterestAmount(),
                    r.getPenaltyAmount(), r.getOutstandingPrincipalBefore(), r.getOutstandingPrincipalAfter(),
                    r.getOutstandingInterestBefore(), r.getOutstandingInterestAfter(), r.getLoanStatusBefore(), r.getLoanStatusAfter());
        }
    }

    /** What a reversal took back from the credit a payment had created. */
    public record CreditTakenBack(
            UUID journalEntryId,
            UUID reversesEntryId,
            rw.terimbere.csams.modules.membercredit.CreditPurpose purpose,
            UUID loanId,
            BigDecimal amount) {

        static CreditTakenBack from(rw.terimbere.csams.modules.membercredit.MemberCreditEntry e) {
            return new CreditTakenBack(e.getId(), e.getReversesEntryId(), e.getPurpose(), e.getLoanId(), e.getAmount());
        }
    }

    public record ReversalResponse(
            UUID id,
            UUID paymentId,
            UUID memberUserId,
            BigDecimal paymentAmount,
            PaymentReversalStatus status,
            String reason,
            UUID requestedBy,
            Instant requestedAt,
            UUID decidedBy,
            Instant decidedAt,
            String decisionNote,
            Instant withdrawnAt,
            String withdrawalNote,
            List<TakenBack> takenBack,
            List<LoanGivenBack> loansGivenBack,
            List<CreditTakenBack> creditsTakenBack) {

        public static ReversalResponse from(
                MemberPaymentReversal r, BigDecimal paymentAmount, List<MemberPaymentPostingReversal> taken) {
            return from(r, paymentAmount, taken, List.of(), List.of());
        }

        public static ReversalResponse from(
                MemberPaymentReversal r,
                BigDecimal paymentAmount,
                List<MemberPaymentPostingReversal> taken,
                List<rw.terimbere.csams.modules.memberpayment.entity.MemberPaymentLoanReversal> loans,
                List<rw.terimbere.csams.modules.membercredit.MemberCreditEntry> credits) {
            return new ReversalResponse(
                    r.getId(),
                    r.getPaymentId(),
                    r.getMemberUserId(),
                    paymentAmount,
                    r.getStatus(),
                    r.getReason(),
                    r.getRequestedBy(),
                    r.getRequestedAt(),
                    r.getDecidedBy(),
                    r.getDecidedAt(),
                    r.getDecisionNote(),
                    r.getWithdrawnAt(),
                    r.getWithdrawalNote(),
                    taken.stream().map(TakenBack::from).toList(),
                    loans.stream().map(LoanGivenBack::from).toList(),
                    credits.stream().map(CreditTakenBack::from).toList());
        }
    }
}
