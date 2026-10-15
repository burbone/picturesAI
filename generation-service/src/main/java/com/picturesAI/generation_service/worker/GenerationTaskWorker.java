package com.picturesAI.generation_service.worker;

import com.picturesAI.generation_service.service.GenerationProcessingService;
import org.springframework.stereotype.Component;

@Component
public class GenerationTaskWorker implements TaskWorker {

    private final GenerationProcessingService service;

    public GenerationTaskWorker(GenerationProcessingService service) {
        this.service = service;
    }

    @Override
    public void process(GenerationTask task) {
        service.process(task);
    }
}