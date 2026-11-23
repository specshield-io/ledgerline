/**
 * The public demo's data lifecycle: seeding an empty database on startup and the nightly reset, both serialised by
 * a ShedLock database lock so several instances never run them at once.
 */
package com.specshield.ledgerline.demo;
