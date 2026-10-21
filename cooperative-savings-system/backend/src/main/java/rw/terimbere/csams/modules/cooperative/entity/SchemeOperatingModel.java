package rw.terimbere.csams.modules.cooperative.entity;

/**
 * How a Saving Scheme operates. A property of the scheme, never of a member. Exactly three models exist; a scheme
 * whose model has not been recorded yet (legacy schemes) simply has no value, there is no "unknown" model.
 *
 * <p>Task 3A records the model only. Model-specific contribution, rotation and hybrid rules are not implemented.
 */
public enum SchemeOperatingModel {
    /** Contributions are retained for savings, lending and investment. */
    ACCUMULATING,
    /** Contributions are distributed to designated members on an agreed rotation. */
    ROTATING,
    /** Part of each contribution is retained, part is distributed through rotation. */
    HYBRID;

    /** Whether the existing lending, payout and investment workflows (built for ACCUMULATING) apply to the model. */
    public boolean supportsAccumulatingWorkflows() {
        return this == ACCUMULATING;
    }
}
