package com.picturesAI.billing_service.repository;

import com.picturesAI.billing_service.entity.DailyUsage;
import com.picturesAI.billing_service.entity.DailyUsageId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.UUID;

public interface DailyUsageRepository extends JpaRepository<DailyUsage, DailyUsageId> {

    @Modifying
    @Query(value = "INSERT INTO daily_usage (user_id, usage_date, used_count) " +
            "VALUES (:userId, :date, 1) " +
            "ON CONFLICT (user_id, usage_date) " +
            "DO UPDATE SET used_count = daily_usage.used_count + 1", nativeQuery = true)
    void increment(@Param("userId") UUID userId, @Param("date") LocalDate date);

    @Modifying
    @Query(value = "UPDATE daily_usage SET used_count = GREATEST(used_count - 1, 0) " +
            "WHERE user_id = :userId AND usage_date = :date", nativeQuery = true)
    void decrement(@Param("userId") UUID userId, @Param("date") LocalDate date);
}