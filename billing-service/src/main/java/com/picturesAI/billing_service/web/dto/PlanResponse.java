package com.picturesAI.billing_service.web.dto;

import java.util.List;

public record PlanResponse(List<PlanItem> plans) {

    public record PlanItem(String name, int dailyLimit) {
    }
}