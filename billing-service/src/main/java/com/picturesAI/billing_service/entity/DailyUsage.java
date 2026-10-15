package com.picturesAI.billing_service.entity;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "daily_usage")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DailyUsage {

    @EmbeddedId
    private DailyUsageId id;

    @Column(name = "used_count", nullable = false)
    private int usedCount;
}