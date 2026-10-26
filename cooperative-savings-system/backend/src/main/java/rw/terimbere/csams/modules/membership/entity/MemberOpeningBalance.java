package rw.terimbere.csams.modules.membership.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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

/**
 * A member's position at the migration cutover date: what they had contributed and what share
 * capital they had paid before the Saving Scheme joined the platform. These are balances, not
 * transactions: no contribution, share purchase or ledger row exists for them.
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "member_opening_balances")
public class MemberOpeningBalance extends BaseEntity {

    @Column(name = "cooperative_id", nullable = false)
    private UUID cooperativeId;

    @Column(name = "member_user_id", nullable = false)
    private UUID memberUserId;

    @Builder.Default
    @Column(name = "opening_contribution_amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal openingContributionAmount = BigDecimal.ZERO;

    @Builder.Default
    @Column(name = "opening_share_count", nullable = false)
    private int openingShareCount = 0;

    @Builder.Default
    @Column(name = "opening_share_capital_amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal openingShareCapitalAmount = BigDecimal.ZERO;

    @Column(name = "cutover_date", nullable = false)
    private LocalDate cutoverDate;

    @Column(name = "historical_import_id", nullable = false)
    private UUID historicalImportId;

    @Column(name = "created_by")
    private UUID createdBy;

    /** Rotated money the member had paid into the running cycle before the cutover (position only, not cash). */
    @Builder.Default
    @Column(name = "opening_rotation_paid", nullable = false, precision = 19, scale = 4)
    private java.math.BigDecimal openingRotationPaid = java.math.BigDecimal.ZERO;

    /** Rotation payouts the member had received in the running cycle before the cutover (position only). */
    @Builder.Default
    @Column(name = "opening_rotation_received", nullable = false, precision = 19, scale = 4)
    private java.math.BigDecimal openingRotationReceived = java.math.BigDecimal.ZERO;

    /** Money the scheme owed the member at the cutover (a liability to the member, not capital). */
    @Builder.Default
    @Column(name = "opening_owed_to_member", nullable = false, precision = 19, scale = 4)
    private java.math.BigDecimal openingOwedToMember = java.math.BigDecimal.ZERO;
}
