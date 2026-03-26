package com.agentbot.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MarketDto {
    private String marketId;
    private String name;
    private BigDecimal bestBid;
    private BigDecimal bestAsk;
    private BigDecimal spread;
    private BigDecimal volume;
    private BigDecimal liquidityScore;
    private BigDecimal edgeScore;
    private BigDecimal rewardEfficiency;
    private BigDecimal competitionDensity;
    private BigDecimal volatilityPenalty;
    private boolean selected;
    private String regime;
}
