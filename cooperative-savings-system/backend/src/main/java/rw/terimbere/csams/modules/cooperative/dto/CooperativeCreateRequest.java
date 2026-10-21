package rw.terimbere.csams.modules.cooperative.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import rw.terimbere.csams.modules.cooperative.entity.SchemeOperatingModel;
import rw.terimbere.csams.modules.member.dto.AssignAdministratorRequest;
import rw.terimbere.csams.modules.subscription.entity.SubscriptionInitialization;
import rw.terimbere.csams.shared.validation.CooperativeFieldRules;
import rw.terimbere.csams.shared.validation.CooperativeRegistrationNumber;
import rw.terimbere.csams.shared.validation.RwandanPhone;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CooperativeCreateRequest {

    @NotBlank
    @Size(max = 255)
    private String name;

    @Size(max = 2000)
    private String description;

    @NotBlank
    @Size(max = 128)
    @CooperativeRegistrationNumber
    private String registrationNumber;

    @NotBlank
    @Email
    @Size(max = 255)
    private String contactEmail;

    @NotBlank
    @Size(max = 32)
    @RwandanPhone
    private String contactPhone;

    @Size(max = 512)
    private String address;

    @Size(min = 3, max = 3)
    private String currency;

    @NotNull
    @Min(1)
    @Max(12)
    private Integer financialYearStartMonth;

    @NotNull
    @DecimalMin(value = "0.0", inclusive = true)
    private BigDecimal monthlyContributionAmount;

    @NotNull
    @Min(CooperativeFieldRules.MIN_DUE_DAY)
    @Max(CooperativeFieldRules.MAX_DUE_DAY)
    private Integer contributionDueDay;

    @NotNull(message = "Registration date is required")
    private LocalDate registrationDate;

    /** Required for every new scheme; there is no default. Validated as text so a bad value is a 400 with a field error. */
    @NotBlank(message = "Operating model is required")
    @Pattern(
            regexp = "ACCUMULATING|ROTATING|HYBRID",
            message = "Operating model must be ACCUMULATING, ROTATING or HYBRID")
    private String operatingModel;

    /**
     * Optional Super Admin onboarding fields. Omitted by older clients.
     * Null {@code subscriptionInitialization} defaults to {@code START_TRIAL}.
     * Null or unspecified {@code president} defers leadership ({@code AWAITING_PRESIDENT}).
     */
    private SubscriptionInitialization subscriptionInitialization;

    @Valid
    private AssignAdministratorRequest president;

    @AssertTrue(message = "Contact email is invalid")
    public boolean isContactEmailValid() {
        return CooperativeFieldRules.isValidEmail(contactEmail);
    }

    @AssertTrue(message = "Registration date must be between 1950-01-01 and today (Africa/Kigali)")
    public boolean isRegistrationDateReasonable() {
        return CooperativeFieldRules.isValidRegistrationDate(registrationDate);
    }

    public SchemeOperatingModel resolvedOperatingModel() {
        return SchemeOperatingModel.valueOf(operatingModel);
    }

    public SubscriptionInitialization resolvedSubscriptionInitialization() {
        return subscriptionInitialization == null
                ? SubscriptionInitialization.START_TRIAL
                : subscriptionInitialization;
    }

    public boolean hasPresidentAssignment() {
        return president != null && president.isSpecified();
    }
}
