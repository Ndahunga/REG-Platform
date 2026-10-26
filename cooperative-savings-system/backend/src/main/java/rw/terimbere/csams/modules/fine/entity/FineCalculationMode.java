package rw.terimbere.csams.modules.fine.entity;

public enum FineCalculationMode {
    FIXED,
    PROGRESSIVE,
    /** Amount by bands of days late (see FineSettings.penaltyBands). */
    BANDED
}
