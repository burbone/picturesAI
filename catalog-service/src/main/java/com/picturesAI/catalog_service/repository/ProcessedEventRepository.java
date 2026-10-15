package com.picturesAI.catalog_service.repository;

import com.picturesAI.catalog_service.domain.ProcessedEvent;
import org.springframework.data.repository.Repository;

public interface ProcessedEventRepository extends Repository<ProcessedEvent, String> {

    ProcessedEvent save(ProcessedEvent event);

    boolean existsById(String id);
}