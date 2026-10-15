package com.picturesAI.tagging_service.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.io.Serializable;
import java.util.UUID;

@Embeddable
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode
public class GenerationTagId implements Serializable {

    @Column(name = "generation_id", nullable = false)
    private UUID generationId;

    @Column(name = "tag_id", nullable = false)
    private UUID tagId;
}