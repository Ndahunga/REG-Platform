package rw.terimbere.csams.modules.rotation.payout;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import lombok.Data;
import lombok.NoArgsConstructor;
import rw.terimbere.csams.modules.memberpayment.entity.PaymentMethod;

/** Request and response shapes of the rotation payout API. */
public final class RotationPayoutDtos {

    private RotationPayoutDtos() {}

    @Data
    @NoArgsConstructor
    public static class RequestPayout {
        /** The period of a period-collection cycle. Not needed when {@link #rotationTurnId} is given. */
        private UUID rotationPeriodId;

        /** The turn of a share-based cycle. */
        private UUID rotationTurnId;

        /** Optional. Defaults to the most the entitlement and the available funds allow (a partial payout if needed). */
        @DecimalMin("0.01")
        @Digits(integer = 15, fraction = 4)
        private BigDecimal amount;

        @Size(max = 2000)
        private String notes;

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

    /** The confirmation that money was ACTUALLY handed over: how, and the reference that proves it. */
    @Data
    @NoArgsConstructor
    public static class ConfirmDisbursement {
        @NotNull private PaymentMethod method;

        @NotBlank
        @Size(max = 128)
        private String reference;

        @Size(max = 2000)
        private String note;

        @JsonAnySetter
        void rejectUnknownField(String name, Object value) {
            throw new IllegalArgumentException("Unknown field: " + name);
        }
    }

    /**
     * What the signed-in user may do with this payout right now, decided by the backend from the same rules that
     * enforce each action. The screen shows or hides buttons from these, never from rules of its own.
     */
    public record PayoutActions(
            boolean canApprove,
            String approveBlockedReason,
            boolean canConfirmDisbursement,
            String confirmBlockedReason,
            boolean canCancel,
            String cancelBlockedReason) {}

    /** Whether a payout can be requested for a period right now, and if not, why (a stable code the screen translates). */
    public record PeriodPayoutEligibility(
            UUID periodId,
            int periodYear,
            int periodMonth,
            UUID recipientUserId,
            boolean canRequestPayout,
            String payoutBlockedReason,
            /** The most a payout request may be for now (verified collections plus pool cover, funds permitting). */
            BigDecimal maxRequestable) {}

    public record PayoutResponse(
            UUID id,
            UUID rotationPeriodId,
            int periodYear,
            int periodMonth,
            UUID recipientUserId,
            String recipientName,
            BigDecimal amount,
            BigDecimal entitlementAtRequest,
            BigDecimal availableAtRequest,
            /** True when the payout is less than the entitlement the system computed (the rest stays owed). */
            boolean partial,
            BigDecimal remainingAfterPayout,
            RotationPayoutStatus status,
            String notes,
            UUID requestedBy,
            Instant requestedAt,
            UUID approvedBy,
            Instant approvedAt,
            String rejectionReason,
            String cancelReason,
            UUID disbursedBy,
            Instant disbursedAt,
            PaymentMethod disbursementMethod,
            String disbursementReference,
            String disbursementNote,
            PayoutActions actions,
            UUID rotationTurnId,
            /** Of the amount, what settles the recipient's overdue contributions instead of being paid in cash. */
            BigDecimal arrearsNetted,
            /** What is handed over: amount less arrears netted. */
            BigDecimal cashAmount) {}

    /** Whether a payout can be requested for a turn of a share-based cycle right now. */
    public record TurnPayoutEligibility(
            UUID turnId,
            UUID periodId,
            int periodYear,
            int periodMonth,
            UUID memberUserId,
            String memberName,
            int shares,
            BigDecimal payoutAmount,
            BigDecimal outstanding,
            /** The member's unpaid contributions of months already due: netted from the payout. */
            BigDecimal arrears,
            boolean canRequestPayout,
            String payoutBlockedReason,
            BigDecimal maxRequestable) {}

    public record EventResponse(
            UUID id,
            RotationPayoutStatus fromStatus,
            RotationPayoutStatus toStatus,
            String actorRole,
            UUID actorUserId,
            String comment,
            Instant createdAt) {}
}
