package rw.terimbere.csams.modules.rotation.entity;

public enum RotationCycleStatus {
    /** Being prepared. Holds no months, so drafts may overlap and never block a replacement. */
    DRAFT,
    /** Holds its months. Recipients may still change before their period begins. */
    ACTIVE,
    /** Reserved for the distribution work (Task 3B-3); not reachable in Task 3B-1. */
    COMPLETED,
    /** Released its months; the rows stay as history. */
    CANCELLED
}
