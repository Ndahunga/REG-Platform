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
            List<PeriodResponse> periods) {}

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
