package com.specshield.ledgerline.persistence;

/** Processing outcome of an inbox row (column {@code order_events_inbox.outcome}). */
public enum InboxOutcome {
    APPLIED,
    DUPLICATE,
    STALE,
    IGNORED,
    ORPHAN,
    REJECTED,
    FAILED
}
