package rw.terimbere.csams.modules.rotation.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import rw.terimbere.csams.modules.rotation.entity.RotationCycleStatus;
import rw.terimbere.csams.modules.rotation.entity.RotationPeriodStatus;

/** Request and response shapes of the rotation API. Rotation data is storage only: it carries no money. */
public final class RotationDtos {

    private RotationDtos() {}

    /**
     * Either the split in money ({@code rotatingUnitAmount}: rotated RWF per share, the rest of the contribution per share
     * is retained) or in basis points. Money is exact and preferred; basis points are derived from it for display.
     */
    public record ConfigurationRequest(
            @NotNull @Min(2000) @Max(2100) Integer effectiveYear,
            @NotNull @Min(1) @Max(12) Integer effectiveMonth,
            @Min(0) @Max(10000) Integer rotatingBps,
            @Min(0) @Max(10000) Integer retainedBps,
            @jakarta.validation.constraints.Positive java.math.BigDecimal rotatingUnitAmount) {}

    public record ConfigurationResponse(
            UUID id,
            int effectiveYear,
            int effectiveMonth,
            int rotatingBps,
            int retainedBps,
            /** Rotated RWF per share, when the split was given in money; null otherwise. */
            java.math.BigDecimal rotatingUnitAmount,
            /** Retained RWF per share (contribution per share minus rotated); null when the split is in basis points. */
            java.math.BigDecimal retainedUnitAmount,
            UUID createdBy,
            Instant createdAt,
            /** True when the configuration has not taken effect and nothing was built on it: it may be deleted. */
            boolean canDelete,
            /** ROTATION_CONFIG_IN_FORCE (its month has begun) or ROTATION_CONFIG_IN_USE (obligations were created under it). */
            String deletionBlockedReason) {}

    public record CycleCreateRequest(
            @NotNull @Min(2000) @Max(2100) Integer startYear,
            @NotNull @Min(1) @Max(12) Integer startMonth,
            @NotEmpty @Size(max = 240) List<@NotNull UUID> recipientUserIds) {}

    /**
     * A share-based cycle: {@code months} monthly slots from the start month; every share has one turn worth
     * {@code payoutPerShare}; a month may hold several turns. {@code turns} places each member's shares in months
     * (monthIndex 1 = the start month).
     */
    public record ShareCycleCreateRequest(
            @NotNull @Min(2000) @Max(2100) Integer startYear,
            @NotNull @Min(1) @Max(12) Integer startMonth,
            @NotNull @Min(1) @Max(240) Integer months,
            @NotNull @jakarta.validation.constraints.Positive java.math.BigDecimal payoutPerShare,
            rw.terimbere.csams.modules.rotation.entity.RotationTurnMode turnMode,
            @NotEmpty @Size(max = 2000) List<@NotNull @jakarta.validation.Valid TurnInput> turns) {}

    /**
     * One turn. {@code openingReceivedAmount}/{@code openingReceivedDate} are used only when a running cycle is imported
     * at migration: what the member already received for this turn before the cutover.
     */
    public record TurnInput(
            @NotNull @Min(1) @Max(240) Integer monthIndex,
            @NotNull UUID memberUserId,
            @NotNull @Min(1) @Max(100) Integer shares,
            @jakarta.validation.constraints.PositiveOrZero java.math.BigDecimal openingReceivedAmount,
            java.time.LocalDate openingReceivedDate) {}

    public record TurnReassignRequest(@NotNull UUID memberUserId, @NotBlank @Size(max = 1000) String reason) {}

    public record TurnResponse(
            UUID id,
            UUID periodId,
            int year,
            int month,
            UUID memberUserId,
            String memberName,
            int shares,
            java.math.BigDecimal payoutAmount,
            /** Received for this turn before the scheme moved to the platform. */
            java.math.BigDecimal openingReceived,
            java.math.BigDecimal paidOut,
            java.math.BigDecimal committed,
            /** Still to be paid for this turn: payout less opening received, paid out and committed. */
            java.math.BigDecimal outstanding,
            /** UPCOMING, DUE (its month has begun), PARTLY_PAID or PAID. */
            String state) {}

    /** Whether the rotated contributions over the cycle cover what the turns pay; a check the officers read, not a rule. */
    public record FundingCheck(
            int sharesWithTurns,
            int sharesHeldByMembers,
            java.math.BigDecimal rotatedPerShareMonthly,
            java.math.BigDecimal expectedCollections,
            java.math.BigDecimal totalPayouts,
            List<String> warnings) {}

    public record CancelRequest(@NotBlank @Size(max = 1000) String reason) {}

    public record RecipientChangeRequest(
            @NotNull UUID recipientUserId, @NotBlank @Size(max = 1000) String reason) {}

    public record PeriodResponse(
            UUID id,
            UUID cycleId,
            int sequenceNumber,
            int year,
            int month,
            UUID recipientUserId,
            String recipientName,
            RotationPeriodStatus status,
            boolean started) {}

    public record CycleResponse(
            UUID id,
            int cycleNumber,
            int startYear,
            int startMonth,
            int periodCount,
            RotationCycleStatus status,
            Instant createdAt,
            Instant activatedAt,
            Instant cancelledAt,
            String cancelReason,
            List<PeriodResponse> periods,
            rw.terimbere.csams.modules.rotation.entity.RotationPayoutMode payoutMode,
            java.math.BigDecimal payoutPerShare,
            rw.terimbere.csams.modules.rotation.entity.RotationTurnMode turnMode,
            /** Turns of a share-based cycle in calendar order; empty for a period-collection cycle. */
            List<TurnResponse> turns,
            /** For a share-based cycle; null otherwise. */
            FundingCheck fundingCheck) {}

    public record RecipientChangeResponse(
            UUID id,
            UUID rotationPeriodId,
            UUID previousRecipientUserId,
            String previousRecipientName,
            UUID newRecipientUserId,
            String newRecipientName,
            String reason,
            UUID changedBy,
            Instant changedAt) {}
}
