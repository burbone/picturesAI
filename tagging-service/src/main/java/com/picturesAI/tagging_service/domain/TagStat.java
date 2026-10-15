package com.picturesAI.tagging_service.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.UUID;

@Entity
@Table(name = "tag_stats")
@Getter
@Setter
@NoArgsConstructor
public class TagStat {

    @Id
    @Column(name = "tag_id", nullable = false, updatable = false)
    private UUID tagId;

    @Column(name = "usage_count", nullable = false)
    private long usageCount;
}