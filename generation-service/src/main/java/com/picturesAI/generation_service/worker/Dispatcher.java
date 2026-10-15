package com.picturesAI.generation_service.worker;

import com.picturesAI.generation_service.domain.Tier;
import com.picturesAI.generation_service.worker.queue.BoundedTierQueue;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;

@Component
public class Dispatcher implements TaskSink {

    public enum Outcome {
        PRO_TAKEN,
        FREE_TAKEN,
        IDLE
    }

    private static final Logger log = LoggerFactory.getLogger(Dispatcher.class);
    private static final Duration DEFAULT_SHUTDOWN_TIMEOUT = Duration.ofSeconds(60);
    private static final Runnable NOOP = () -> {
    };

    private final DispatcherProperties props;
    private final BoundedTierQueue premiumQueue;
    private final BoundedTierQueue freeQueue;
    private final TaskWorker worker;
    private final ExecutorService pool;
    private final AtomicInteger activePro = new AtomicInteger();
    private final AtomicInteger activeFree = new AtomicInteger();
    private final Duration shutdownTimeout;

    private volatile boolean running;
    private Thread loopThread;

    @Autowired
    public Dispatcher(DispatcherProperties props, TaskWorker worker) {
        this(props, worker, DEFAULT_SHUTDOWN_TIMEOUT);
    }

    Dispatcher(DispatcherProperties props, TaskWorker worker, Duration shutdownTimeout) {
        this.props = props;
        this.worker = worker;
        this.shutdownTimeout = shutdownTimeout;
        this.premiumQueue = new BoundedTierQueue(props.premiumQueueCapacity());
        this.freeQueue = new BoundedTierQueue(props.freeQueueCapacity());
        this.pool = Executors.newFixedThreadPool(props.totalSlots(), r -> {
            Thread t = new Thread(r, "provider-pool");
            t.setDaemon(false);
            return t;
        });
    }

    @PostConstruct
    void start() {
        running = true;
        loopThread = new Thread(this::run, "dispatcher-loop");
        loopThread.setDaemon(false);
        loopThread.start();
        log.info("Dispatcher started: totalSlots={}, proReserved={}, freeReserved={}",
                props.totalSlots(), props.slotsProReserved(), props.slotsFreeReserved());
    }

    @PreDestroy
    public void shutdown() {
        running = false;
        if (loopThread != null) {
            loopThread.interrupt();
            try {
                loopThread.join(shutdownTimeout.toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        pool.shutdown();
        try {
            if (!pool.awaitTermination(shutdownTimeout.toMillis(), TimeUnit.MILLISECONDS)) {
                log.warn("Provider pool did not terminate in {}ms, forcing shutdown",
                        shutdownTimeout.toMillis());
                pool.shutdownNow();
                pool.awaitTermination(shutdownTimeout.toMillis(), TimeUnit.MILLISECONDS);
            }
        } catch (InterruptedException e) {
            pool.shutdownNow();
            Thread.currentThread().interrupt();
        }
        log.info("Dispatcher stopped");
    }

    public boolean offerPro(GenerationTask task) {
        return offerPro(task, NOOP);
    }

    @Override
    public boolean offerPro(GenerationTask task, Runnable onComplete) {
        return premiumQueue.offer(task, onComplete);
    }

    public boolean offerFree(GenerationTask task) {
        return offerFree(task, NOOP);
    }

    @Override
    public boolean offerFree(GenerationTask task, Runnable onComplete) {
        return freeQueue.offer(task, onComplete);
    }

    public Outcome runOnce() {
        if (!premiumQueue.isEmpty() && activePro.get() < props.slotsProReserved()) {
            var t = premiumQueue.poll();
            if (t != null) {
                activePro.incrementAndGet();
                submit(t);
                return Outcome.PRO_TAKEN;
            }
        }
        if (!freeQueue.isEmpty() && activeFree.get() < props.slotsFreeReserved()) {
            var t = freeQueue.poll();
            if (t != null) {
                activeFree.incrementAndGet();
                submit(t);
                return Outcome.FREE_TAKEN;
            }
        }
        if (!premiumQueue.isEmpty() && totalActive() < props.totalSlots()) {
            var t = premiumQueue.poll();
            if (t != null) {
                activePro.incrementAndGet();
                submit(t);
                return Outcome.PRO_TAKEN;
            }
        }
        if (!freeQueue.isEmpty() && totalActive() < props.totalSlots()) {
            var t = freeQueue.poll();
            if (t != null) {
                activeFree.incrementAndGet();
                submit(t);
                return Outcome.FREE_TAKEN;
            }
        }
        return Outcome.IDLE;
    }

    public int activePro() {
        return activePro.get();
    }

    public int activeFree() {
        return activeFree.get();
    }

    public int totalActive() {
        return activePro.get() + activeFree.get();
    }

    public int premiumQueueDepth() {
        return premiumQueue.size();
    }

    public int freeQueueDepth() {
        return freeQueue.size();
    }

    public double premiumQueueFillRatio() {
        return premiumQueue.fillRatio();
    }

    public double freeQueueFillRatio() {
        return freeQueue.fillRatio();
    }

    @Override
    public boolean shouldPauseConsumer(Tier tier) {
        return queueFor(tier).isFull();
    }

    @Override
    public boolean shouldResumeConsumer(Tier tier) {
        return queueFor(tier).fillRatio() <= 0.8;
    }

    private BoundedTierQueue queueFor(Tier tier) {
        return tier == Tier.PRO ? premiumQueue : freeQueue;
    }

    private void submit(QueuedTask queued) {
        final boolean pro = queued.task().tier() == Tier.PRO;
        pool.submit(() -> {
            try {
                worker.process(queued.task());
            } catch (Exception e) {
                log.warn("Worker failed for generation {}: {}",
                        queued.task().generationId(), e.toString());
            } finally {
                try {
                    queued.onComplete().run();
                } catch (Exception e) {
                    log.warn("onComplete failed for generation {}: {}",
                            queued.task().generationId(), e.toString());
                }
                if (pro) {
                    activePro.decrementAndGet();
                } else {
                    activeFree.decrementAndGet();
                }
            }
        });
    }

    private void run() {
        while (running) {
            Outcome outcome = runOnce();
            if (outcome == Outcome.IDLE) {
                LockSupport.parkNanos(Duration.ofMillis(props.idlePollMs()).toNanos());
                if (Thread.interrupted()) {
                    return;
                }
            }
        }
    }
}