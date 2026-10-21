package rw.terimbere.csams.modules.contribution;

import java.math.BigDecimal;
import rw.terimbere.csams.shared.utilities.MoneyUtils;

public final class ShareAmountCalculator {

    public static final int DEFAULT_SHARE_COUNT = 0;
    public static final int MAX_SHARE_COUNT = 1000;

    private ShareAmountCalculator() {}

    /** Owned/approved shares. Null or negative values become 0; never invents a founding share. */
    public static int ownedShareCount(Integer shareCount) {
        if (shareCount == null || shareCount < 0) {
            return 0;
        }
        return Math.min(shareCount, MAX_SHARE_COUNT);
    }

    public static int normalizeShareCount(Integer shareCount) {
        return ownedShareCount(shareCount);
    }

    public static BigDecimal expectedMonthly(BigDecimal unitAmount, Integer shareCount) {
        BigDecimal unit = unitAmount == null ? BigDecimal.ZERO : unitAmount;
        return MoneyUtils.scaleForStorage(
                unit.multiply(BigDecimal.valueOf(normalizeShareCount(shareCount))));
    }
}
