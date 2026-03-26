package com.agentbot.engine.model;

import lombok.Builder;
import lombok.Data;
import java.math.BigDecimal;

@Data
@Builder
public class RiskLimits {
    @Builder.Default
    private BigDecimal maxPositionPerMarket = new BigDecimal("200");
    @Builder.Default
    private BigDecimal maxGlobalExposure = new BigDecimal("800");
    @Builder.Default
    private BigDecimal maxVolatilityThreshold = new BigDecimal("0.10");
    @Builder.Default
    private BigDecimal maxOrderSize = new BigDecimal("50");
    @Builder.Default
    private int maxActiveOrdersPerMarket = 2;
    @Builder.Default
    private int maxActiveOrdersGlobal = 10;
    @Builder.Default
    private BigDecimal minSpreadToQuote = new BigDecimal("0.02");
    @Builder.Default
    private BigDecimal minLiquidityScore = new BigDecimal("3.0");
    @Builder.Default
    private BigDecimal minRewardScore = new BigDecimal("3.0");
}
