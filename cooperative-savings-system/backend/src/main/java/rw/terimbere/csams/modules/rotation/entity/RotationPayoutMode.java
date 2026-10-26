package rw.terimbere.csams.modules.rotation.entity;

/** How a cycle pays its recipients. */
public enum RotationPayoutMode {
    /** One recipient per month receives what that month collected (the original behaviour). */
    PERIOD_COLLECTION,
    /** Every share has one turn worth a fixed amount; a month may hold several turns; paid from the rotation pool. */
    FIXED_PER_SHARE
}
