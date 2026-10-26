package rw.terimbere.csams.modules.rotation.entity;

/** How the turns of a member holding several shares are placed in a share-based cycle. */
public enum RotationTurnMode {
    /** Each share is its own turn, possibly in different months (the default). */
    ONE_SHARE_PER_TURN,
    /** A member's shares are all paid together in one month. */
    ALL_SHARES_TOGETHER,
    /** Officers group turns per month freely (e.g. two recipients a month to fit the cycle into a year). */
    GROUPED_BY_MONTH
}
