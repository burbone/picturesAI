package com.picturesAI.generation_service.worker.offset;

import java.util.NavigableSet;
import java.util.TreeSet;

public class PartitionOffsetTracker {

    private final Object lock = new Object();
    private final NavigableSet<Long> completed = new TreeSet<>();
    private boolean initialized = false;
    private long lowWaterMark = -1;

    public void register(long offset) {
        synchronized (lock) {
            if (!initialized) {
                lowWaterMark = offset - 1;
                initialized = true;
            }
        }
    }

    public void markCompleted(long offset) {
        synchronized (lock) {
            if (!initialized) {
                lowWaterMark = offset - 1;
                initialized = true;
            }
            if (offset <= lowWaterMark) {
                return;
            }
            completed.add(offset);
            while (completed.remove(lowWaterMark + 1)) {
                lowWaterMark++;
            }
        }
    }

    public long safeCommitOffset() {
        synchronized (lock) {
            return lowWaterMark + 1;
        }
    }

    public void reset() {
        synchronized (lock) {
            initialized = false;
            lowWaterMark = -1;
            completed.clear();
        }
    }
}