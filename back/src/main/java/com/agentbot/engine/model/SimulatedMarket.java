package com.agentbot.engine.model;

import lombok.Data;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;

@Data
public class SimulatedMarket {
    private final String marketId;
    private final String name;

    private BigDecimal midPrice;
    private BigDecimal bestBid;
    private BigDecimal bestAsk;
    private BigDecimal spread;
    private BigDecimal volume;
    private BigDecimal volatility;
    private BigDecimal rewardScore;
    private BigDecimal competitionLevel;
    private Instant lastUpdate;

    private BigDecimal previousMid;
    private BigDecimal shortTermMomentum = BigDecimal.ZERO;
    private BigDecimal realizedVolatility = BigDecimal.ZERO;
    private final Deque<BigDecimal> priceHistory = new ArrayDeque<>();
    private static final int HISTORY_SIZE = 20;

    private VolatilityRegime regime = VolatilityRegime.CALM;
    private int regimeTicksRemaining = 0;

    private BigDecimal bidQueueDepth = new BigDecimal("300");
    private BigDecimal askQueueDepth = new BigDecimal("300");

    private BigDecimal competitorBestBid;
    private BigDecimal competitorBestAsk;

    private boolean informedFlowActive = false;
    private BigDecimal informedDirection = BigDecimal.ZERO;

    private BigDecimal tickVolume = BigDecimal.ZERO;

    public enum VolatilityRegime { CALM, NORMAL, VOLATILE, CRISIS }

    public SimulatedMarket(String marketId, String name, BigDecimal initialMid) {
        this.marketId = marketId;
        this.name = name;
        this.midPrice = initialMid;
        this.previousMid = initialMid;
        this.bestBid = initialMid.subtract(new BigDecimal("0.01"));
        this.bestAsk = initialMid.add(new BigDecimal("0.01"));
        this.spread = new BigDecimal("0.02");
        this.volume = BigDecimal.ZERO;
        this.volatility = new BigDecimal("0.02");
        this.rewardScore = new BigDecimal("5.0");
        this.competitionLevel = new BigDecimal("0.5");
        this.lastUpdate = Instant.now();
        this.competitorBestBid = this.bestBid;
        this.competitorBestAsk = this.bestAsk;
    }

    public void updateMomentum() {
        BigDecimal move = midPrice.subtract(previousMid);
        priceHistory.addLast(move);
        if (priceHistory.size() > HISTORY_SIZE) {
            priceHistory.removeFirst();
        }

        if (priceHistory.size() >= 3) {
            BigDecimal sum = BigDecimal.ZERO;
            int count = 0;
            for (BigDecimal m : priceHistory) {
                sum = sum.add(m);
                count++;
                if (count >= 5) break;
            }
            shortTermMomentum = sum.divide(BigDecimal.valueOf(count), 6, RoundingMode.HALF_UP);
        }

        if (priceHistory.size() >= 5) {
            BigDecimal sumSq = BigDecimal.ZERO;
            BigDecimal avg = BigDecimal.ZERO;
            for (BigDecimal m : priceHistory) avg = avg.add(m);
            avg = avg.divide(BigDecimal.valueOf(priceHistory.size()), 6, RoundingMode.HALF_UP);
            for (BigDecimal m : priceHistory) {
                BigDecimal diff = m.subtract(avg);
                sumSq = sumSq.add(diff.multiply(diff));
            }
            realizedVolatility = sumSq.divide(BigDecimal.valueOf(priceHistory.size()), 8, RoundingMode.HALF_UP);
            try {
                realizedVolatility = BigDecimal.valueOf(Math.sqrt(realizedVolatility.doubleValue()))
                        .setScale(6, RoundingMode.HALF_UP);
            } catch (Exception e) {
                realizedVolatility = volatility;
            }
        }

        previousMid = midPrice;
    }

    public boolean isTrending() {
        return shortTermMomentum.abs().compareTo(new BigDecimal("0.003")) > 0;
    }

    public boolean isHighVolatility() {
        return regime == VolatilityRegime.VOLATILE || regime == VolatilityRegime.CRISIS
                || realizedVolatility.compareTo(volatility.multiply(new BigDecimal("1.5"))) > 0;
    }

    public boolean isCrisis() {
        return regime == VolatilityRegime.CRISIS;
    }

    public BigDecimal getVolatilityMultiplier() {
        return switch (regime) {
            case CALM -> new BigDecimal("0.5");
            case NORMAL -> BigDecimal.ONE;
            case VOLATILE -> new BigDecimal("2.5");
            case CRISIS -> new BigDecimal("5.0");
        };
    }

    public BigDecimal getLiquidityScore() {
        if (spread.compareTo(BigDecimal.ZERO) == 0) return BigDecimal.TEN;
        BigDecimal rawScore = BigDecimal.ONE.divide(spread, 4, RoundingMode.HALF_UP)
                .multiply(new BigDecimal("0.1"));
        return rawScore.min(BigDecimal.TEN).max(BigDecimal.ZERO);
    }
}
