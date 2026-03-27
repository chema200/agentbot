package com.agentbot.polymarket;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Data
@Configuration
@ConfigurationProperties(prefix = "shadow")
public class ShadowConfig {

    private boolean enabled = true;
    private int maxMarkets = 5;
    private long refreshIntervalMs = 10000;
    private long cycleIntervalMs = 3000;
    private double maxCapitalSharePerMarket = 0.25;
    private double regimePenaltyCalm = 1.0;
    private double regimePenaltyNormal = 0.8;
    private double regimePenaltyVolatile = 0.35;
    private double regimePenaltyCrisis = 0.0;
    private boolean blockVolatileMarkets = false;
    private int cooldownCycles = 15;
    private double minEdgeAfterPenalty = 0.05;

    private double edgeClampMin = 0.0;
    private double edgeClampMax = 1.0;
    private double maxYesExposure = 200.0;
    private double maxNoExposure = 200.0;
    private double maxNetExposure = 100.0;
    private double inventoryPenaltyK = 0.5;
    private boolean rebalanceBiasEnabled = true;
    private int minOrderSize = 5;
    private int maxOrderSize = 25;
    private double sizeScaleVolatile = 0.5;
    private double sizeScaleCrisis = 0.0;
    private double sizeScaleNormal = 0.8;
    private double sizeScaleCalm = 1.0;
    private int minActiveMarkets = 2;
    private double quoteAggressiveness = 0.5; // 0=wide, 1=tight quotes relative to spread
    private int cycleSummaryInterval = 15;
    private int staleOrderTimeoutSec = 30;
}
