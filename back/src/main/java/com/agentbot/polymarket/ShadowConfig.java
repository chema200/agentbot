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
    private double inventoryMinScale = 0.15;
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

    // Market Guard (classification + proportional penalties + strong cooldowns)
    private double guardFillsShareThreshold = 0.35;
    private int guardMaxConsecutiveNegative = 6;
    private int guardSoftCooldownCycles = 100;
    private int guardHardCooldownCycles = 200;
    private int guardQualitySnapshotInterval = 15;

    private int guardWinnerMinFills5m = 2;
    private double guardMaxPenaltyWinner = 0.10;
    private double guardWinnerChurnOverrideStale = 0.92;

    private int guardMinFillsForClassification = 2;
    private double guardToxicRateClassification = 0.34;
    private double guardToxicRateHardCooldown = 0.34;

    private double guardChurnStaleRate5m = 0.88;
    private int guardChurnMinStaleEvents5m = 8;
    private int guardChurnMaxFills5m = 2;

    private double guardLoserPnl5mThreshold = -0.03;

    private double guardPenaltyBaseWinner = 0.0;
    private double guardPenaltyBaseNeutral = 0.03;
    private double guardPenaltyBaseLoser = 0.12;
    private double guardPenaltyBaseToxic = 0.45;
    private double guardPenaltyBaseHighChurn = 0.10;

    private double guardLossPenaltyK = 2.5;
    private double guardToxicityPenaltyExtra = 0.25;
    private double guardToxicityPenaltyK = 0.35;
    private double guardChurnPenaltyK = 0.22;
    private double guardConcentrationShareSoft = 0.28;
    private double guardConcentrationPenaltyKLoss = 0.55;
    private double guardConcentrationPenaltyKWinner = 0.12;

    private double guardHardCooldownPnl5mThreshold = -0.02;
    private double guardExtremeChurnStale5m = 0.92;
    private int guardExtremeChurnMinStale = 10;

    private double guardSoftCooldownPnl5mThreshold = -0.08;
    private int guardSoftCooldownMinFills5m = 5;

    private double guardDisabledSessionPnlThreshold = -0.50;
    private int guardDisabledMinSessionFills = 15;

    // Recovery mode
    private long recoveryIdleThresholdSec = 120;
    private double recoveryMinEdge = 0.015;
    private double recoveryGuardRelaxation = 0.80;
    private double recoveryAggrBoost = 0.05;
}
