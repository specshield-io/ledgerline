package com.specshield.ledgerline.chaos;

/** Errors the chaos API reports as problems. */
public final class ChaosExceptions {

    private ChaosExceptions() {}

    public static final class ScenarioNotFound extends RuntimeException {
        public ScenarioNotFound(String name) {
            super("Unknown scenario '" + name + "'. Known: " + known() + ".");
        }
    }

    public static final class RunNotFound extends RuntimeException {
        public RunNotFound(String id) {
            super("Scenario run " + id + " does not exist (runs are kept for an hour).");
        }
    }

    /** All slots are taken. */
    public static final class CapacityExceeded extends RuntimeException {
        public CapacityExceeded(int max) {
            super("At most " + max + " scenario runs can be in progress at once. Try again in a few seconds.");
        }
    }

    /** The deployment lacks what the scenario needs (MockPay, or the demo merchant). */
    public static final class Unavailable extends RuntimeException {
        public Unavailable(String reason) {
            super(reason);
        }
    }

    static String known() {
        StringBuilder names = new StringBuilder();
        for (Scenario s : Scenario.values()) {
            names.append(names.isEmpty() ? "" : ", ").append(s.slug());
        }
        return names.toString();
    }
}
