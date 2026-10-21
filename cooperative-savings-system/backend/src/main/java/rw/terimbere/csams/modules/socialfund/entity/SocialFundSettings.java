package rw.terimbere.csams.modules.socialfund.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.math.BigDecimal;
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
@Table(name = "social_fund_settings")
public class SocialFundSettings extends BaseEntity {

    @Column(name = "cooperative_id", nullable = false, unique = true)
    private UUID cooperativeId;

    @Builder.Default
    @Column(name = "suggested_contribution_amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal suggestedContributionAmount = BigDecimal.ZERO;

    @Builder.Default
    @Column(name = "enabled", nullable = false)
    private boolean enabled = true;
}
