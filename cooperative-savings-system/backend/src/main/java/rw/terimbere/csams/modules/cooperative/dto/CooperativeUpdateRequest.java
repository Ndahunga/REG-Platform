package rw.terimbere.csams.modules.cooperative.dto;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import rw.terimbere.csams.shared.validation.CooperativeFieldRules;
import rw.terimbere.csams.shared.validation.CooperativeRegistrationNumber;
import rw.terimbere.csams.shared.validation.RwandanPhone;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CooperativeUpdateRequest {

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

    @AssertTrue(message = "Contact email is invalid")
    public boolean isContactEmailValid() {
        return CooperativeFieldRules.isValidEmail(contactEmail);
    }

    @AssertTrue(message = "Registration date must be between 1950-01-01 and today (Africa/Kigali)")
    public boolean isRegistrationDateReasonable() {
        return CooperativeFieldRules.isValidRegistrationDate(registrationDate);
    }
}
