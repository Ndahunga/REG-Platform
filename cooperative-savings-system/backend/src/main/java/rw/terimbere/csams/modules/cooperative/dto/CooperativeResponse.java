package rw.terimbere.csams.modules.cooperative.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import rw.terimbere.csams.modules.cooperative.entity.CooperativeOnboardingState;
import rw.terimbere.csams.modules.cooperative.entity.CooperativeStatus;
import rw.terimbere.csams.modules.cooperative.entity.SchemeOperatingModel;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CooperativeResponse {

    private UUID id;
    private String name;
    private String description;
    private String registrationNumber;
    private String contactEmail;
    private String contactPhone;
    private String address;
    private String currency;
    private int financialYearStartMonth;
    private BigDecimal monthlyContributionAmount;
    private int contributionDueDay;
    private String logoFileKey;
    private String logoUrl;
    private CooperativeStatus status;
    private CooperativeOnboardingState onboardingState;
    /** Null only for a legacy scheme that has not been classified yet. */
    private SchemeOperatingModel operatingModel;
    private LocalDate registrationDate;
    private UUID createdBy;
    private Instant createdAt;
    private Instant updatedAt;
}
