package com.picturesAI.generation_service.worker.queue;

import com.picturesAI.generation_service.worker.GenerationTask;
import com.picturesAI.generation_service.worker.QueuedTask;

import java.util.concurrent.ArrayBlockingQueue;

public class BoundedTierQueue {

    private final ArrayBlockingQueue<QueuedTask> queue;
    private final int capacity;

    public BoundedTierQueue(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive");
        }
        this.capacity = capacity;
        this.queue = new ArrayBlockingQueue<>(capacity);
    }

    public boolean offer(GenerationTask task, Runnable onComplete) {
        return queue.offer(new QueuedTask(task, onComplete));
    }

    public QueuedTask poll() {
        return queue.poll();
    }

    public int size() {
        return queue.size();
    }

    public int capacity() {
        return capacity;
    }

    public boolean isEmpty() {
        return queue.isEmpty();
    }

    public boolean isFull() {
        return queue.size() >= capacity;
    }

    public double fillRatio() {
        return (double) queue.size() / capacity;
    }
}