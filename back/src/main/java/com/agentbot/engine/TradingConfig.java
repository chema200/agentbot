package com.agentbot.engine;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Data
@Configuration
@ConfigurationProperties(prefix = "trading")
public class TradingConfig {
    private double maxCapitalSharePerMarket = 0.25;
    private double regimePenaltyCalm = 1.0;
    private double regimePenaltyNormal = 0.8;
    private double regimePenaltyVolatile = 0.35;
    private double regimePenaltyCrisis = 0.0;
    private boolean blockVolatileMarkets = false;
    private int cooldownCycles = 15;
    private double minEdgeAfterPenalty = 0.3;
    private double maxYesExposure = 500.0;
    private double maxNoExposure = 500.0;
    private double maxNetExposure = 300.0;
    private int snapshotInterval = 15;
    private int topMarkets = 5;
}
