package rw.terimbere.csams.modules.contribution.obligation;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import rw.terimbere.csams.modules.cooperative.entity.SchemeOperatingModel;

/** Request and response shapes of the obligation API. Obligations are expectations, never collected cash. */
public final class ObligationDtos {

    private ObligationDtos() {}

    public record DueDayRequest(
            @NotNull @Min(1) @Max(31) Integer dueDay,
            @NotNull @AssertTrue(message = "Confirmation is required: the due day applies from next month and is kept as history")
                    Boolean confirmed) {}

    public record DueDayEntry(int effectiveYear, int effectiveMonth, int dueDay, Instant createdAt) {}

    /**
     * @param configured false for a scheme that never set a due day: no overdue dates are produced for it
     * @param currentDueDay the day in force this month, or null when none is in force yet
     * @param pending a change that takes effect from a later month, or null
     */
    public record DueDayResponse(
            boolean configured, Integer currentDueDay, DueDayEntry pending, List<DueDayEntry> history) {}

    public record MaterializeRequest(
            @NotNull @Min(2000) @Max(2100) Integer year, @NotNull @Min(1) @Max(12) Integer month) {}

    /** How many obligation rows were created (existing rows are never touched or recreated). */
    public record MaterializeResponse(int year, int month, int created, int alreadyPresent, int activeMembers) {}

    /**
     * What a payment would add to the rotating and retained portions of one obligation. A read-only plan: nothing is
     * allocated, posted or recorded as received.
     */
    public record AttributionPlan(
            UUID contributionId,
            UUID memberUserId,
            int year,
            int month,
            SchemeOperatingModel operatingModel,
            BigDecimal amount,
            BigDecimal outstandingBefore,
            BigDecimal outstandingAfter,
            BigDecimal rotatingDelta,
            BigDecimal retainedDelta,
            UUID rotationPeriodId,
            UUID originalScheduledRecipientUserId) {}
}
