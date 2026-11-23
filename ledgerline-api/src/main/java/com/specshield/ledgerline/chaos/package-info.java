/**
 * Chaos scenarios for visitors: each run creates its own synthetic order and drives it through a failure mode with
 * real signed HTTP from MockPay (duplicates, tampering, replay, reordering) or by switching the demo merchant off or
 * to slow. Runs never touch existing orders. At most a few run at once.
 */
package com.specshield.ledgerline.chaos;
