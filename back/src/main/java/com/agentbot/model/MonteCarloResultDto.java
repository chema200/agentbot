package com.agentbot.model;

import lombok.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MonteCarloResultDto {
    private String mcRunId;
    private int numSeeds;
    private String stressProfile;
    private int cyclesPerRun;
    private BigDecimal avgPnl;
    private BigDecimal medianPnl;
    private BigDecimal stdPnl;
    private BigDecimal minPnl;
    private BigDecimal maxPnl;
    private BigDecimal winRate;
    private BigDecimal avgDrawdown;
    private BigDecimal maxDrawdown;
    private BigDecimal avgFills;
    private BigDecimal avgToxicFills;
    private BigDecimal sharpeRatio;
    private long elapsedMs;
    private Instant createdAt;
    private List<BacktestResultDto> individualRuns;
}
