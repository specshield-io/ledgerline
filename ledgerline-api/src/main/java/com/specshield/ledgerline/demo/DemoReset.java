package com.specshield.ledgerline.demo;

import com.specshield.ledgerline.application.DemoDataService;
import java.time.Duration;
import net.javacrumbs.shedlock.core.ClockProvider;
import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockingTaskExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Runs the demo data jobs under one ShedLock lock ({@value #LOCK}), so the nightly reset, an admin reset and startup
 * seeding never overlap, on one instance or several. A caller that finds the lock taken does nothing and is told so.
 *
 * <p>Lock times use ShedLock's own clock (system UTC), not the injected application {@code Clock}: a lease shared
 * between processes has to be measured in real time, and tests move the application clock freely.
 */
@Component
public class DemoReset implements ApplicationRunner {

    public static final String LOCK = "demo-data";

    /** A reset takes well under a second; the lock is released early if the process dies mid-run. */
    static final Duration LOCK_AT_MOST = Duration.ofMinutes(10);

    /**
     * The nightly run keeps the lock a little longer, so an instance whose clock is a few seconds behind does not
     * start a second reset right after the first.
     */
    static final Duration NIGHTLY_LOCK_AT_LEAST = Duration.ofSeconds(30);

    private static final Logger log = LoggerFactory.getLogger(DemoReset.class);

    public enum Outcome {
        DONE,
        ALREADY_RUNNING
    }

    private final DemoDataService data;
    private final LockingTaskExecutor locks;
    private final DemoProperties properties;

    public DemoReset(DemoDataService data, LockingTaskExecutor locks, DemoProperties properties) {
        this.data = data;
        this.locks = locks;
        this.properties = properties;
    }

    /** Wipes and reseeds now, unless another reset or seeding holds the lock. */
    public Outcome reset(String actor) {
        return locked(Duration.ZERO, () -> data.reset(actor));
    }

    @Scheduled(cron = "${ledgerline.demo.reset-cron:0 0 3 * * *}", zone = "UTC")
    void nightly() {
        Outcome outcome = locked(NIGHTLY_LOCK_AT_LEAST, () -> data.reset("system:nightly-reset"));
        log.info("nightly demo reset: {}", outcome);
    }

    /** Seeds an empty database (startup). */
    public Outcome seedIfEmpty() {
        return locked(Duration.ZERO, () -> {
            int seeded = data.seedIfEmpty();
            if (seeded > 0) {
                log.info("seeded {} synthetic demo orders", seeded);
            }
            return seeded;
        });
    }

    @Override
    public void run(ApplicationArguments args) {
        if (properties.seedOnStartup()) {
            seedIfEmpty();
        }
    }

    private Outcome locked(Duration atLeast, java.util.function.Supplier<Integer> task) {
        LockConfiguration lock = new LockConfiguration(ClockProvider.now(), LOCK, LOCK_AT_MOST, atLeast);
        try {
            LockingTaskExecutor.TaskResult<Integer> result = locks.executeWithLock(task::get, lock);
            return result.wasExecuted() ? Outcome.DONE : Outcome.ALREADY_RUNNING;
        } catch (RuntimeException | Error e) {
            throw e;
        } catch (Throwable e) {
            throw new IllegalStateException("demo data job failed", e);
        }
    }
}
