package com.specshield.ledgerline.demo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.specshield.ledgerline.application.DemoDataService;
import java.io.IOException;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import net.javacrumbs.shedlock.core.DefaultLockingTaskExecutor;
import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockingTaskExecutor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;

class DemoResetTest {

    private final DemoDataService data = mock(DemoDataService.class);
    private final AtomicReference<LockConfiguration> lastLock = new AtomicReference<>();

    /** The real executor over a lock provider that always grants the lock and remembers its configuration. */
    private final LockingTaskExecutor grantingLocks = new DefaultLockingTaskExecutor(config -> {
        lastLock.set(config);
        return Optional.of(() -> {});
    });

    private DemoReset reset(LockingTaskExecutor locks, boolean seedOnStartup) {
        return new DemoReset(data, locks, new DemoProperties(seedOnStartup, null, null, null));
    }

    @Test
    @DisplayName("LL-N07 the nightly job resets under the demo-data lock and keeps it at least 30 s")
    void nightly() {
        reset(grantingLocks, false).nightly();

        verify(data).reset("system:nightly-reset");
        assertThat(lastLock.get().getName()).isEqualTo(DemoReset.LOCK);
        assertThat(lastLock.get().getLockAtLeastFor()).isEqualTo(Duration.ofSeconds(30));
        assertThat(lastLock.get().getLockAtMostFor()).isEqualTo(Duration.ofMinutes(10));
    }

    @Test
    @DisplayName("startup seeding runs only when enabled")
    void startup() {
        when(data.seedIfEmpty()).thenReturn(25);

        reset(grantingLocks, false).run(new DefaultApplicationArguments());
        verify(data, never()).seedIfEmpty();

        reset(grantingLocks, true).run(new DefaultApplicationArguments());
        verify(data).seedIfEmpty();
        assertThat(lastLock.get().getLockAtLeastFor()).isEqualTo(Duration.ZERO);
    }

    @Test
    @DisplayName("a checked failure inside the lock is reported as IllegalStateException")
    void checkedFailure() throws Throwable {
        LockingTaskExecutor failing = mock(LockingTaskExecutor.class);
        when(failing.executeWithLock(any(LockingTaskExecutor.TaskWithResult.class), any()))
                .thenThrow(new IOException("simulated"));

        assertThatIllegalStateException().isThrownBy(() -> reset(failing, false).reset("test"));
    }
}
