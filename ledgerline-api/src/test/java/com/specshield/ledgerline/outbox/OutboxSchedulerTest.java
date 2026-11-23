package com.specshield.ledgerline.outbox;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class OutboxSchedulerTest {

    private final OutboxDispatcher dispatcher = mock(OutboxDispatcher.class);
    private final OutboxWatchdog watchdog = mock(OutboxWatchdog.class);
    private final OutboxScheduler scheduler =
            new OutboxScheduler(dispatcher, watchdog, new OutboxProperties(true, null, 20, null, null, 0, null, null));

    @Test
    @DisplayName("a tick keeps dispatching while batches come back full, and stops at the first partial batch")
    void drainsBacklog() {
        when(dispatcher.dispatchOnce()).thenReturn(20, 20, 5);

        scheduler.dispatch();

        verify(dispatcher, times(3)).dispatchOnce();
    }

    @Test
    @DisplayName("a tick runs at most a bounded number of batches, even under a large backlog")
    void boundedPerTick() {
        when(dispatcher.dispatchOnce()).thenReturn(20);

        scheduler.dispatch();

        verify(dispatcher, times(OutboxScheduler.MAX_BATCHES_PER_TICK)).dispatchOnce();
    }

    @Test
    @DisplayName("the watchdog tick releases stuck rows")
    void watchdogTick() {
        scheduler.watchdog();

        verify(watchdog).releaseStuck();
    }
}
