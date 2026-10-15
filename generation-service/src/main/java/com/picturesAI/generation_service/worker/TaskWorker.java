package com.picturesAI.generation_service.worker;

public interface TaskWorker {

    void process(GenerationTask task);
}