package rw.terimbere.csams.modules.share.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ShareValuationResponse {

    private UUID cooperativeId;
    private String currency;

    /** Authoritative liquid group funds (approved/finalized cash only). */
    private BigDecimal availableFunds;

    /** Outstanding loan principal still owed (ACTIVE/OVERDUE). */
    private BigDecimal outstandingLoans;

    /** Remaining contracted loan interest still owed (ACTIVE/OVERDUE). */
    private BigDecimal unpaidInterest;

    /** Assessed unpaid/partially-paid fine outstanding amounts. */
    private BigDecimal unpaidPenalties;

    /** Remaining capital in ACTIVE/PARTIALLY_RETURNED investments. */
    private BigDecimal otherAssets;

    /**
     * Obligations owed by the Ikimina: recipient entitlements out of verified rotating money, plus rotating money not
     * yet reconciled. Always {@code verifiedRotatingLiabilities + unreconciledRotatingFunds}.
     */
    private BigDecimal liabilities;

    /** What rotation recipients are still owed out of money whose receipt was verified. */
    private BigDecimal verifiedRotatingLiabilities;

    /** Rotating money recorded without verification: held for its recipient, not payable until reconciled. */
    private BigDecimal unreconciledRotatingFunds;

    /**
     * Money known to be rotating whose original period cannot be established: held (a liability), never member capital,
     * not payable until attributed. Included in {@code liabilities}.
     */
    private BigDecimal unresolvedRotatingFunds;

    /**
     * Money whose character is genuinely unknown. Not a liability and not assigned to anyone: it stays inside the
     * valuation as cash, and is reported here so nobody mistakes it for classified member capital. Not part of
     * {@code liabilities}.
     */
    private BigDecimal unclassifiedFunds;

    /**
     * availableFunds + outstandingLoans + unpaidInterest + unpaidPenalties + otherAssets − liabilities
     */
    private BigDecimal totalIkiminaValue;

    /** Sum of owned share_count on ACTIVE memberships. */
    private long totalExistingShares;

    /** totalIkiminaValue / totalExistingShares, or configured baseSharePrice when shares are 0. */
    private BigDecimal currentShareValue;

    private Instant calculatedAt;

    /** Optional cooperative setting used only when totalExistingShares == 0. */
    private BigDecimal baseSharePrice;

    private boolean usedBaseSharePrice;
    private boolean canPurchase;
    private String purchaseBlockedReason;
}
