package com.picturesAI.generation_service.worker.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.picturesAI.generation_service.domain.Tier;
import com.picturesAI.generation_service.worker.TaskSink;
import com.picturesAI.generation_service.worker.offset.OffsetTrackerRegistry;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("!test")
public class ConsumerPro extends AbstractGenerationConsumer {

    public ConsumerPro(
            @Qualifier("proKafkaConsumer") KafkaConsumer<String, String> consumer,
            TaskSink sink,
            OffsetTrackerRegistry registry,
            ObjectMapper mapper,
            ConsumerProperties props) {
        super(Tier.PRO, props.proTopic(), consumer, sink, registry, mapper, props);
    }
}