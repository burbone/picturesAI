package com.picturesAI.generation_service.worker;

import com.picturesAI.generation_service.domain.Tier;

public interface TaskSink {

    boolean offerPro(GenerationTask task, Runnable onComplete);

    boolean offerFree(GenerationTask task, Runnable onComplete);

    boolean shouldPauseConsumer(Tier tier);

    boolean shouldResumeConsumer(Tier tier);
}