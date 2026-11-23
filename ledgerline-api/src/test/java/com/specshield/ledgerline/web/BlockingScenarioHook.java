package com.specshield.ledgerline.web;

import com.specshield.ledgerline.chaos.ScenarioHook;
import com.specshield.ledgerline.chaos.ScenarioRun;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** LL-I22: when armed, every run waits here (holding its slot) until released. */
public final class BlockingScenarioHook implements ScenarioHook {

    private final AtomicReference<CountDownLatch> gate = new AtomicReference<>();

    public void arm() {
        gate.set(new CountDownLatch(1));
    }

    public void release() {
        CountDownLatch latch = gate.getAndSet(null);
        if (latch != null) {
            latch.countDown();
        }
    }

    @Override
    public void beforeSteps(ScenarioRun run) throws InterruptedException {
        CountDownLatch latch = gate.get();
        if (latch != null && !latch.await(30, TimeUnit.SECONDS)) {
            throw new IllegalStateException("BlockingScenarioHook was never released");
        }
    }
}
