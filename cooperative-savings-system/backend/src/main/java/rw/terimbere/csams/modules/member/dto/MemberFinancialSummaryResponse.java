package rw.terimbere.csams.modules.member.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MemberFinancialSummaryResponse {

    private UUID cooperativeId;
    private UUID memberUserId;
    private String memberName;
    private String membershipStatus;
    private LocalDate membershipDate;
    private String currency;

    /** Sum of paid regular monthly contributions. */
    private BigDecimal regularContributions;

    /** Sum of approved special contributions. */
    private BigDecimal specialContributions;

    /** regularContributions + specialContributions. */
    private BigDecimal actualContributions;

    /** Sum of expected regular contribution amounts. */
    private BigDecimal expectedContributions;

    /** Sum of outstanding regular contribution amounts. */
    private BigDecimal outstandingContributions;

    /** Sum of disbursed loan principal. */
    private BigDecimal loansReceived;

    private BigDecimal outstandingLoanPrincipal;
    private BigDecimal outstandingLoanInterest;

    /** Sum of repaid principal + interest on the member's loans. */
    private BigDecimal totalLoanRepayments;

    /** Sum of fine totalAmount excluding cancelled. */
    private BigDecimal totalFines;

    /** Sum of fine outstandingAmount for unpaid / partially paid. */
    private BigDecimal unpaidFines;

    /** Sum of approved fine payment amounts. */
    private BigDecimal approvedFinePayments;

    /** Sum of approved social fund contributions. */
    private BigDecimal socialContributions;

    /**
     * Member actual contributions as a percentage of cooperative actual contributions
     * (regular + special). Null when cooperative total is zero.
     */
    private BigDecimal contributionPercentage;

    /** Sum of confirmed/paid payout line amounts for this member. */
    private BigDecimal recentPayoutTotal;

    private Integer sharesHeld;
    private BigDecimal currentShareValue;
    private BigDecimal totalShareValue;

    /**
     * Opening balance imported at the migration cutover (not a transaction). Already included in
     * {@link #regularContributions}; shown separately so it can be labelled
     * "Opening balance through {@link #openingCutoverDate}".
     */
    private BigDecimal openingContributions;

    private LocalDate openingCutoverDate;

    /** Shares owned at the cutover (already part of {@link #sharesHeld}). */
    private Integer openingShareCount;

    /** Share capital historically paid before OuWealth (not valued: current share value is separate). */
    private BigDecimal openingShareCapital;

    /** Opening share capital + approved share purchases made in OuWealth. */
    private BigDecimal contributedShareCapital;
}
