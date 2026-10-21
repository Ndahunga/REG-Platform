package rw.terimbere.csams.modules.rotation.pool;

/** What a rotating-pool record does to the pool set aside for recipients. */
public enum PoolEntryType {
    /** The pool that existed before verified contributions started to arrive (at most one per scheme). */
    OPENING,
    /** Money added to the pool later. */
    TOP_UP,
    /** Part of the pool released or spent. */
    EXPENSE
}
