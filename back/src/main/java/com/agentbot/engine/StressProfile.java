package com.agentbot.engine;

import lombok.Getter;
import java.math.BigDecimal;

@Getter
public enum StressProfile {

    BASELINE(0.5, 5.0, 0.02, 0.03, 150, 0.5),
    HIGH_COMPETITION(0.85, 5.0, 0.02, 0.03, 150, 0.5),
    LOW_REWARD(0.5, 1.5, 0.02, 0.03, 150, 0.5),
    HIGH_VOLATILITY(0.5, 5.0, 0.06, 0.08, 150, 0.5),
    FREQUENT_INFORMED(0.5, 5.0, 0.02, 0.03, 150, 0.5),
    HIGH_LATENCY(0.5, 5.0, 0.02, 0.03, 500, 0.5),
    CRISIS_HEAVY(0.5, 5.0, 0.04, 0.06, 150, 0.5);

    private final double competitionLevel;
    private final double rewardScore;
    private final double baseVolatility;
    private final double maxVolatility;
    private final int maxLatencyMs;
    private final double informedFlowProb;

    StressProfile(double competitionLevel, double rewardScore,
                  double baseVolatility, double maxVolatility,
                  int maxLatencyMs, double informedFlowProb) {
        this.competitionLevel = competitionLevel;
        this.rewardScore = rewardScore;
        this.baseVolatility = baseVolatility;
        this.maxVolatility = maxVolatility;
        this.maxLatencyMs = maxLatencyMs;
        this.informedFlowProb = informedFlowProb;
    }

    public BigDecimal getCompetitionLevelBD() { return BigDecimal.valueOf(competitionLevel); }
    public BigDecimal getRewardScoreBD() { return BigDecimal.valueOf(rewardScore); }
    public BigDecimal getBaseVolatilityBD() { return BigDecimal.valueOf(baseVolatility); }

    public double getCrisisProbability() {
        return this == CRISIS_HEAVY ? 0.15 : 0.02;
    }

    public double getInformedFlowRate() {
        return this == FREQUENT_INFORMED ? 0.20 : 0.05;
    }
}
