package com.specshield.ledgerline.chaos;

/** Called on the run's thread before its steps. A no-op in production; tests use it to hold runs open (LL-I22). */
@FunctionalInterface
public interface ScenarioHook {

    void beforeSteps(ScenarioRun run) throws InterruptedException;
}
