package rw.terimbere.csams.modules.cooperative.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import rw.terimbere.csams.shared.common.entity.SoftDeletableEntity;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "cooperatives")
public class Cooperative extends SoftDeletableEntity {

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "description", length = 2000)
    private String description;

    @Column(name = "registration_number", length = 128)
    private String registrationNumber;

    @Column(name = "contact_email")
    private String contactEmail;

    @Column(name = "contact_phone", length = 32)
    private String contactPhone;

    @Column(name = "address", length = 512)
    private String address;

    @Builder.Default
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "currency", nullable = false, length = 3)
    private String currency = "RWF";

    @Builder.Default
    @Column(name = "financial_year_start_month", nullable = false)
    private int financialYearStartMonth = 1;

    @Builder.Default
    @Column(name = "monthly_contribution_amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal monthlyContributionAmount = BigDecimal.ZERO;

    @Builder.Default
    @Column(name = "contribution_due_day", nullable = false)
    private int contributionDueDay = 1;

    @Column(name = "logo_file_key", length = 512)
    private String logoFileKey;

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private CooperativeStatus status = CooperativeStatus.ACTIVE;

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(name = "onboarding_state", nullable = false, length = 32)
    private CooperativeOnboardingState onboardingState = CooperativeOnboardingState.COMPLETE;

    @Column(name = "registration_date")
    private LocalDate registrationDate;

    /**
     * How the scheme operates. Insert-only ({@code updatable = false}): no ordinary save can ever change it, so
     * generic updates cannot overwrite it. Legacy schemes have no value until an authorized administrator
     * classifies them once through {@code CooperativeRepository#classifyOperatingModelIfUnset}.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "operating_model", length = 32, updatable = false)
    private SchemeOperatingModel operatingModel;

    @Column(name = "created_by")
    private UUID createdBy;
}
