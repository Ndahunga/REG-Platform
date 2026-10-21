package rw.terimbere.csams.modules.memberpayment.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import lombok.Data;
import lombok.NoArgsConstructor;
import rw.terimbere.csams.modules.membercredit.CreditPurpose;
import rw.terimbere.csams.modules.memberpayment.entity.PaymentAllocationType;
import rw.terimbere.csams.modules.memberpayment.entity.PaymentMethod;

/**
 * Member payment submission. There is intentionally no member field: a submission is always recorded for the
 * authenticated caller, and any unknown field (for example an attempt to name another member) is rejected.
 * Receipt-analysis data is never accepted from the client.
 *
 * <p>The idempotency key travels in the {@code Idempotency-Key} header, not in the body.
 */
@Data
@NoArgsConstructor
public class MemberPaymentSubmitRequest {

    @NotNull
    @DecimalMin("0.01")
    @Digits(integer = 15, fraction = 4)
    private BigDecimal amount;

    @NotNull private LocalDate paymentDate;

    @NotNull private PaymentMethod paymentMethod;

    @Size(max = 128)
    private String transactionReference;

    @Size(max = 512)
    private String evidenceFileKey;

    @Size(max = 2000)
    private String notes;

    /** The payer asks for a manual review of a receipt that could not be confirmed as clear. */
    private Boolean manualReview;

    @NotEmpty
    @Size(max = 50)
    @Valid
    private List<AllocationRequest> allocations;

    @JsonAnySetter
    void rejectUnknownField(String name, Object value) {
        throw new IllegalArgumentException("Unknown field: " + name);
    }

    public MemberPaymentCreateCommand toCommand(String idempotencyKey) {
        return new MemberPaymentCreateCommand(
                null,
                amount,
                paymentDate,
                paymentMethod,
                transactionReference,
                evidenceFileKey,
                idempotencyKey,
                notes,
                allocations == null
                        ? List.of()
                        : allocations.stream().map(AllocationRequest::toAllocation).toList(),
                manualReview);
    }

    @Data
    @NoArgsConstructor
    public static class AllocationRequest {

        @NotNull private PaymentAllocationType type;

        @NotNull
        @DecimalMin("0.01")
        @Digits(integer = 15, fraction = 4)
        private BigDecimal amount;

        @Min(2000)
        @Max(2100)
        private Integer periodYear;

        @Min(1)
        @Max(12)
        private Integer periodMonth;

        private UUID loanId;

        /** MEMBER_CREDIT only: why the excess is held. */
        private CreditPurpose creditPurpose;

        @DecimalMin("0")
        @Digits(integer = 15, fraction = 4)
        private BigDecimal principalAmount;

        @DecimalMin("0")
        @Digits(integer = 15, fraction = 4)
        private BigDecimal interestAmount;

        @DecimalMin("0")
        @Digits(integer = 15, fraction = 4)
        private BigDecimal penaltyAmount;

        @JsonAnySetter
        void rejectUnknownField(String name, Object value) {
            throw new IllegalArgumentException("Unknown field: " + name);
        }

        MemberPaymentCreateCommand.Allocation toAllocation() {
            return new MemberPaymentCreateCommand.Allocation(
                    type, amount, periodYear, periodMonth, loanId, principalAmount, interestAmount, penaltyAmount,
                    creditPurpose);
        }
    }
}
