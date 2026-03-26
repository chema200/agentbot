package com.agentbot.engine.model;

import lombok.Builder;
import lombok.Data;
import java.math.BigDecimal;

@Data
@Builder
public class MarketScore {
    private String marketId;
    private String marketName;
    private BigDecimal totalScore;
    private BigDecimal rewardComponent;
    private BigDecimal spreadComponent;
    private BigDecimal competitionComponent;
    private BigDecimal liquidityComponent;
    private BigDecimal riskComponent;
    private int rank;

    private BigDecimal edgeScore;
    private BigDecimal rewardEfficiency;
    private BigDecimal competitionDensity;
    private BigDecimal rewardPerCompetition;
    private BigDecimal volatilityPenalty;
    @Builder.Default
    private boolean selected = false;
    private String rejectionReason;
}
