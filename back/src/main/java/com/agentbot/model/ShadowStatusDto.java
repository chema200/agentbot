package com.agentbot.model;

import lombok.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ShadowStatusDto {
    private String status;
    private boolean wsConnected;
    private long cycleCount;
    private int liveMarkets;
    private int activeOrders;
    private int totalFills;
    private Instant startedAt;
    private Map<String, Object> metrics;
}
