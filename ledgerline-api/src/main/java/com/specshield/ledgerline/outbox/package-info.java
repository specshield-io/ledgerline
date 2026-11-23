/**
 * Transactional outbox relay: claims due rows, delivers them as signed HTTP notifications to the merchant endpoint,
 * retries with exponential backoff and full jitter, and parks rows that keep failing as DEAD. Delivery is
 * at-least-once; receivers deduplicate by {@code Ledgerline-Event-Id}. See docs/adr/0003-transactional-outbox.md.
 */
package com.specshield.ledgerline.outbox;
