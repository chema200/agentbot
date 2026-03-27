package com.agentbot.model;

import lombok.*;
import java.math.BigDecimal;
import java.time.Instant;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BacktestResultDto {
    private String runId;
    private long seed;
    private String stressProfile;
    private int cycles;
    private int simulatedDurationSec;
    private BigDecimal totalPnl;
    private BigDecimal tradingPnl;
    private BigDecimal rewardPnl;
    private int totalFills;
    private int toxicFills;
    private BigDecimal totalFees;
    private BigDecimal maxExposure;
    private BigDecimal maxDrawdown;
    private BigDecimal finalInventoryNet;
    private BigDecimal avgProfitPerFill;
    private BigDecimal adverseSelectionRate;
    private BigDecimal winRate;
    private int activeMarkets;
    private long elapsedMs;
    private Instant createdAt;
}
