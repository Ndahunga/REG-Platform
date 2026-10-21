package rw.terimbere.csams.modules.specialcontribution.entity;

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
import rw.terimbere.csams.shared.common.entity.BaseEntity;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "special_contribution_campaigns")
public class SpecialContributionCampaign extends BaseEntity {

    @Column(name = "cooperative_id", nullable = false)
    private UUID cooperativeId;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "purpose", length = 512)
    private String purpose;

    @Column(name = "description", length = 2000)
    private String description;

    @Column(name = "suggested_amount", precision = 19, scale = 4)
    private BigDecimal suggestedAmount;

    @Column(name = "target_amount", precision = 19, scale = 4)
    private BigDecimal targetAmount;

    @Column(name = "start_date")
    private LocalDate startDate;

    @Column(name = "end_date")
    private LocalDate endDate;

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private SpecialCampaignStatus status = SpecialCampaignStatus.DRAFT;

    @Column(name = "created_by")
    private UUID createdBy;
}
