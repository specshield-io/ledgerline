package com.specshield.ledgerline.application;

import java.util.List;
import java.util.UUID;

/** Port for the append-only audit log. */
public interface AuditTrail {

    void append(AuditRecord record);

    /** Entries for one order, oldest first. */
    List<AuditRecord> forOrder(UUID orderId);
}
