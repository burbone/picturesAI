package com.picturesAI.generation_service.worker;

import com.picturesAI.generation_service.domain.Tier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntSupplier;

import static org.assertj.core.api.Assertions.assertThat;

class DispatcherTest {

    private static final Duration TEST_SHUTDOWN_TIMEOUT = Duration.ofSeconds(1);

    private Dispatcher dispatcher;

    @AfterEach
    void tearDown() {
        if (dispatcher != null) {
            dispatcher.shutdown();
        }
    }

    private Dispatcher with(int total, int proReserved, int freeReserved, TaskWorker worker) {
        DispatcherProperties props = new DispatcherProperties(
                total, proReserved, freeReserved, 10, 10, 10);
        dispatcher = new Dispatcher(props, worker, TEST_SHUTDOWN_TIMEOUT);
        return dispatcher;
    }

    @Test
    void takesProWhenProQueued() {
        BlockingWorker worker = new BlockingWorker();
        Dispatcher d = with(3, 2, 1, worker);

        d.offerPro(task(Tier.PRO));
        Dispatcher.Outcome outcome = d.runOnce();

        assertThat(outcome).isEqualTo(Dispatcher.Outcome.PRO_TAKEN);
        assertThat(worker.arrivalReached(1)).isTrue();
        assertThat(d.activePro()).isEqualTo(1);
        assertThat(d.activeFree()).isZero();
    }

    @Test
    void takesFreeWhenOnlyFreeQueued() {
        BlockingWorker worker = new BlockingWorker();
        Dispatcher d = with(3, 2, 1, worker);

        d.offerFree(task(Tier.FREE));
        Dispatcher.Outcome outcome = d.runOnce();

        assertThat(outcome).isEqualTo(Dispatcher.Outcome.FREE_TAKEN);
        assertThat(worker.arrivalReached(1)).isTrue();
        assertThat(d.activeFree()).isEqualTo(1);
    }

    @Test
    void proTakesPriorityOverFreeWhenBothReservesOpen() {
        BlockingWorker worker = new BlockingWorker();
        Dispatcher d = with(3, 2, 1, worker);

        d.offerFree(task(Tier.FREE));
        d.offerPro(task(Tier.PRO));

        assertThat(d.runOnce()).isEqualTo(Dispatcher.Outcome.PRO_TAKEN);
        assertThat(d.runOnce()).isEqualTo(Dispatcher.Outcome.FREE_TAKEN);
        assertThat(worker.arrivalReached(2)).isTrue();
        assertThat(d.activePro()).isEqualTo(1);
        assertThat(d.activeFree()).isEqualTo(1);
    }

    @Test
    void freeFillsReserveEvenWhenProQueueIsNotEmpty() {
        BlockingWorker worker = new BlockingWorker();
        Dispatcher d = with(3, 2, 1, worker);

        d.offerPro(task(Tier.PRO));
        d.offerPro(task(Tier.PRO));
        d.runOnce();
        d.runOnce();
        assertThat(worker.arrivalReached(2)).isTrue();
        assertThat(d.activePro()).isEqualTo(2);

        d.offerPro(task(Tier.PRO));
        d.offerFree(task(Tier.FREE));

        assertThat(d.runOnce()).isEqualTo(Dispatcher.Outcome.FREE_TAKEN);
        assertThat(d.activeFree()).isEqualTo(1);
    }

    @Test
    void proSpillsOverWhenFreeReserveUnused() {
        BlockingWorker worker = new BlockingWorker();
        Dispatcher d = with(3, 2, 1, worker);

        d.offerPro(task(Tier.PRO));
        d.offerPro(task(Tier.PRO));
        d.runOnce();
        d.runOnce();
        assertThat(worker.arrivalReached(2)).isTrue();

        d.offerPro(task(Tier.PRO));
        Dispatcher.Outcome outcome = d.runOnce();

        assertThat(outcome).isEqualTo(Dispatcher.Outcome.PRO_TAKEN);
        assertThat(d.activePro()).isEqualTo(3);
        assertThat(d.activeFree()).isZero();
    }

    @Test
    void respectsTotalSlotLimit() {
        BlockingWorker worker = new BlockingWorker();
        Dispatcher d = with(3, 2, 1, worker);

        d.offerPro(task(Tier.PRO));
        d.offerPro(task(Tier.PRO));
        d.offerFree(task(Tier.FREE));
        d.runOnce();
        d.runOnce();
        d.runOnce();
        assertThat(worker.arrivalReached(3)).isTrue();
        assertThat(d.totalActive()).isEqualTo(3);

        d.offerPro(task(Tier.PRO));
        assertThat(d.runOnce()).isEqualTo(Dispatcher.Outcome.IDLE);
    }

    @Test
    void idleWhenBothQueuesEmpty() {
        Dispatcher d = with(3, 2, 1, new BlockingWorker());

        assertThat(d.runOnce()).isEqualTo(Dispatcher.Outcome.IDLE);
    }

    @Test
    void counterDecrementedAfterCompletion() {
        ImmediateWorker worker = new ImmediateWorker();
        Dispatcher d = with(3, 2, 1, worker);

        d.offerPro(task(Tier.PRO));
        d.runOnce();

        awaitZero(d::activePro);
        assertThat(d.activePro()).isZero();
        assertThat(worker.processed()).isEqualTo(1);
    }

    @Test
    void workerExceptionDecrementsCounter() {
        TaskWorker throwsWorker = task -> {
            throw new RuntimeException("boom");
        };
        Dispatcher d = with(3, 2, 1, throwsWorker);

        d.offerPro(task(Tier.PRO));
        d.runOnce();

        awaitZero(d::activePro);
    }

    @Test
    void pauseWhenQueueFull() {
        DispatcherProperties props = new DispatcherProperties(2, 1, 1, 1, 1, 10);
        dispatcher = new Dispatcher(props, new BlockingWorker(), TEST_SHUTDOWN_TIMEOUT);

        assertThat(dispatcher.shouldPauseConsumer(Tier.FREE)).isFalse();
        assertThat(dispatcher.shouldResumeConsumer(Tier.FREE)).isTrue();

        dispatcher.offerFree(task(Tier.FREE));

        assertThat(dispatcher.shouldPauseConsumer(Tier.FREE)).isTrue();
        assertThat(dispatcher.shouldResumeConsumer(Tier.FREE)).isFalse();
    }

    @Test
    void offerReturnsFalseWhenFull() {
        DispatcherProperties props = new DispatcherProperties(1, 1, 0, 1, 1, 10);
        dispatcher = new Dispatcher(props, new BlockingWorker(), TEST_SHUTDOWN_TIMEOUT);

        assertThat(dispatcher.offerPro(task(Tier.PRO))).isTrue();
        assertThat(dispatcher.offerPro(task(Tier.PRO))).isFalse();
    }

    private GenerationTask task(Tier tier) {
        return new GenerationTask(UUID.randomUUID(), tier);
    }

    private void awaitZero(IntSupplier supplier) {
        long deadline = System.currentTimeMillis() + 2000;
        while (System.currentTimeMillis() < deadline && supplier.getAsInt() != 0) {
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    static class BlockingWorker implements TaskWorker {

        private final CountDownLatch release = new CountDownLatch(1);
        private final AtomicInteger arrivals = new AtomicInteger();

        @Override
        public void process(GenerationTask task) {
            arrivals.incrementAndGet();
            try {
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        boolean arrivalReached(int expected) {
            try {
                long deadline = System.currentTimeMillis() + 2000;
                while (System.currentTimeMillis() < deadline && arrivals.get() < expected) {
                    Thread.sleep(10);
                }
                return arrivals.get() >= expected;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
    }

    static class ImmediateWorker implements TaskWorker {

        private final AtomicInteger processed = new AtomicInteger();

        @Override
        public void process(GenerationTask task) {
            processed.incrementAndGet();
        }

        int processed() {
            return processed.get();
        }
    }
}