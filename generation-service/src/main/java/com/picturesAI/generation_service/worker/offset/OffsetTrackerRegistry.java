package com.picturesAI.generation_service.worker.offset;

import org.apache.kafka.common.TopicPartition;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;

@Component
public class OffsetTrackerRegistry {

    private final ConcurrentHashMap<TopicPartition, PartitionOffsetTracker> trackers =
            new ConcurrentHashMap<>();

    public PartitionOffsetTracker get(TopicPartition partition) {
        return trackers.computeIfAbsent(partition, k -> new PartitionOffsetTracker());
    }

    public PartitionOffsetTracker remove(TopicPartition partition) {
        return trackers.remove(partition);
    }

    public void reset(TopicPartition partition) {
        PartitionOffsetTracker tracker = trackers.get(partition);
        if (tracker != null) {
            tracker.reset();
        }
    }
}