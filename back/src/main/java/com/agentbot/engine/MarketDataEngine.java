package com.agentbot.engine;

import com.agentbot.engine.model.SimulatedMarket;
import com.agentbot.engine.model.SimulatedMarket.VolatilityRegime;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.concurrent.ThreadLocalRandom;

@Slf4j
@Component
public class MarketDataEngine {

    private static final BigDecimal TICK_SIZE = new BigDecimal("0.01");
    private static final BigDecimal MIN_PRICE = new BigDecimal("0.02");
    private static final BigDecimal MAX_PRICE = new BigDecimal("0.98");

    public void tick(SimulatedMarket market) {
        ThreadLocalRandom rng = ThreadLocalRandom.current();

        updateRegime(market, rng);
        simulateInformedFlow(market, rng);
        updatePrice(market, rng);
        simulateCompetition(market, rng);
        updateQueueDepth(market, rng);

        market.updateMomentum();

        jitterVolatility(market, rng);
        jitterRewardAndCompetition(market, rng);
    }

    private void updateRegime(SimulatedMarket market, ThreadLocalRandom rng) {
        if (market.getRegimeTicksRemaining() > 0) {
            market.setRegimeTicksRemaining(market.getRegimeTicksRemaining() - 1);
            return;
        }

        double roll = rng.nextDouble();
        VolatilityRegime current = market.getRegime();

        VolatilityRegime next;
        int duration;

        switch (current) {
            case CALM -> {
                if (roll < 0.60) {
                    next = VolatilityRegime.CALM;
                    duration = rng.nextInt(10, 30);
                } else if (roll < 0.90) {
                    next = VolatilityRegime.NORMAL;
                    duration = rng.nextInt(8, 20);
                } else if (roll < 0.98) {
                    next = VolatilityRegime.VOLATILE;
                    duration = rng.nextInt(3, 8);
                } else {
                    next = VolatilityRegime.CRISIS;
                    duration = rng.nextInt(2, 5);
                }
            }
            case NORMAL -> {
                if (roll < 0.30) {
                    next = VolatilityRegime.CALM;
                    duration = rng.nextInt(10, 25);
                } else if (roll < 0.75) {
                    next = VolatilityRegime.NORMAL;
                    duration = rng.nextInt(5, 15);
                } else if (roll < 0.93) {
                    next = VolatilityRegime.VOLATILE;
                    duration = rng.nextInt(3, 10);
                } else {
                    next = VolatilityRegime.CRISIS;
                    duration = rng.nextInt(2, 5);
                }
            }
            case VOLATILE -> {
                if (roll < 0.15) {
                    next = VolatilityRegime.CALM;
                    duration = rng.nextInt(8, 20);
                } else if (roll < 0.50) {
                    next = VolatilityRegime.NORMAL;
                    duration = rng.nextInt(5, 12);
                } else if (roll < 0.85) {
                    next = VolatilityRegime.VOLATILE;
                    duration = rng.nextInt(3, 8);
                } else {
                    next = VolatilityRegime.CRISIS;
                    duration = rng.nextInt(2, 4);
                }
            }
            case CRISIS -> {
                if (roll < 0.10) {
                    next = VolatilityRegime.CALM;
                    duration = rng.nextInt(5, 15);
                } else if (roll < 0.40) {
                    next = VolatilityRegime.NORMAL;
                    duration = rng.nextInt(5, 10);
                } else if (roll < 0.75) {
                    next = VolatilityRegime.VOLATILE;
                    duration = rng.nextInt(3, 8);
                } else {
                    next = VolatilityRegime.CRISIS;
                    duration = rng.nextInt(1, 4);
                }
            }
            default -> {
                next = VolatilityRegime.NORMAL;
                duration = 10;
            }
        }

        if (next != current) {
            log.debug("Market {} regime: {} -> {} (duration: {})", market.getMarketId(), current, next, duration);
        }
        market.setRegime(next);
        market.setRegimeTicksRemaining(duration);
    }

    private void simulateInformedFlow(SimulatedMarket market, ThreadLocalRandom rng) {
        double informedProb = switch (market.getRegime()) {
            case CALM -> 0.02;
            case NORMAL -> 0.05;
            case VOLATILE -> 0.12;
            case CRISIS -> 0.25;
        };

        if (market.isInformedFlowActive()) {
            if (rng.nextDouble() < 0.4) {
                market.setInformedFlowActive(false);
                market.setInformedDirection(BigDecimal.ZERO);
            }
            return;
        }

        if (rng.nextDouble() < informedProb) {
            market.setInformedFlowActive(true);
            BigDecimal direction = rng.nextBoolean() ? BigDecimal.ONE : BigDecimal.ONE.negate();
            market.setInformedDirection(direction);
            log.debug("Informed flow started on {} direction: {}", market.getMarketId(), direction);
        }
    }

    private void updatePrice(SimulatedMarket market, ThreadLocalRandom rng) {
        BigDecimal volMultiplier = market.getVolatilityMultiplier();

        BigDecimal drift = BigDecimal.valueOf(rng.nextGaussian())
                .multiply(market.getVolatility())
                .multiply(volMultiplier)
                .multiply(TICK_SIZE)
                .setScale(4, RoundingMode.HALF_UP);

        if (market.isInformedFlowActive()) {
            BigDecimal informedPush = market.getInformedDirection()
                    .multiply(market.getVolatility())
                    .multiply(volMultiplier)
                    .multiply(new BigDecimal("3"))
                    .multiply(TICK_SIZE);
            drift = drift.add(informedPush);
        }

        BigDecimal newMid = market.getMidPrice().add(drift)
                .max(MIN_PRICE).min(MAX_PRICE)
                .setScale(2, RoundingMode.HALF_UP);

        BigDecimal baseHalfSpread = market.getSpread().divide(BigDecimal.TWO, 4, RoundingMode.HALF_UP);
        BigDecimal regimeSpreadWiden = switch (market.getRegime()) {
            case CALM -> new BigDecimal("0.8");
            case NORMAL -> BigDecimal.ONE;
            case VOLATILE -> new BigDecimal("1.5");
            case CRISIS -> new BigDecimal("3.0");
        };
        BigDecimal halfSpread = baseHalfSpread.multiply(regimeSpreadWiden);
        BigDecimal jitter = BigDecimal.valueOf(rng.nextDouble(-0.003, 0.003));
        halfSpread = halfSpread.add(jitter).max(new BigDecimal("0.005")).setScale(4, RoundingMode.HALF_UP);

        BigDecimal newBid = newMid.subtract(halfSpread).max(MIN_PRICE).setScale(2, RoundingMode.HALF_UP);
        BigDecimal newAsk = newMid.add(halfSpread).min(MAX_PRICE).setScale(2, RoundingMode.HALF_UP);

        if (newBid.compareTo(newAsk) >= 0) {
            newAsk = newBid.add(TICK_SIZE);
        }

        BigDecimal baseVolume = BigDecimal.valueOf(rng.nextInt(50, 500));
        BigDecimal tickVol = baseVolume.multiply(volMultiplier).setScale(0, RoundingMode.HALF_UP);

        market.setPreviousMid(market.getMidPrice());
        market.setMidPrice(newMid);
        market.setBestBid(newBid);
        market.setBestAsk(newAsk);
        market.setSpread(newAsk.subtract(newBid));
        market.setVolume(market.getVolume().add(tickVol));
        market.setTickVolume(tickVol);
        market.setLastUpdate(Instant.now());
    }

    private void simulateCompetition(SimulatedMarket market, ThreadLocalRandom rng) {
        double competitionIntensity = market.getCompetitionLevel().doubleValue();
        double regimeBoost = switch (market.getRegime()) {
            case CALM -> 0.85;
            case NORMAL -> 1.0;
            case VOLATILE -> 1.25;
            case CRISIS -> 1.5;
        };
        double effectiveIntensity = Math.min(1.0, competitionIntensity * regimeBoost);

        BigDecimal compBid = market.getBestBid();
        BigDecimal compAsk = market.getBestAsk();

        if (rng.nextDouble() < effectiveIntensity * 0.6) {
            BigDecimal undercut = TICK_SIZE.multiply(BigDecimal.valueOf(rng.nextInt(1, 4)));
            compBid = market.getBestBid().add(undercut);
            if (compBid.compareTo(market.getMidPrice()) >= 0) {
                compBid = market.getMidPrice().subtract(TICK_SIZE);
            }
            compBid = compBid.max(MIN_PRICE);
        }

        if (rng.nextDouble() < effectiveIntensity * 0.6) {
            BigDecimal undercut = TICK_SIZE.multiply(BigDecimal.valueOf(rng.nextInt(1, 4)));
            compAsk = market.getBestAsk().subtract(undercut);
            if (compAsk.compareTo(market.getMidPrice()) <= 0) {
                compAsk = market.getMidPrice().add(TICK_SIZE);
            }
            compAsk = compAsk.min(MAX_PRICE);
        }

        market.setCompetitorBestBid(compBid.setScale(2, RoundingMode.HALF_UP));
        market.setCompetitorBestAsk(compAsk.setScale(2, RoundingMode.HALF_UP));
    }

    private void updateQueueDepth(SimulatedMarket market, ThreadLocalRandom rng) {
        BigDecimal baseDepth = switch (market.getRegime()) {
            case CALM -> new BigDecimal("500");
            case NORMAL -> new BigDecimal("300");
            case VOLATILE -> new BigDecimal("150");
            case CRISIS -> new BigDecimal("50");
        };

        BigDecimal volNorm = market.getTickVolume()
                .divide(new BigDecimal("500"), 4, RoundingMode.HALF_UP)
                .min(new BigDecimal("2.5"));
        BigDecimal activityShrink = BigDecimal.ONE
                .subtract(volNorm.multiply(new BigDecimal("0.12")))
                .max(new BigDecimal("0.5"));

        BigDecimal informedStress = market.isInformedFlowActive() ? new BigDecimal("0.75") : BigDecimal.ONE;

        BigDecimal depthCore = baseDepth.multiply(activityShrink).multiply(informedStress);

        BigDecimal bidJitter = BigDecimal.valueOf(rng.nextDouble(0.85, 1.15));
        BigDecimal askJitter = BigDecimal.valueOf(rng.nextDouble(0.85, 1.15));

        market.setBidQueueDepth(depthCore.multiply(bidJitter).setScale(0, RoundingMode.HALF_UP));
        market.setAskQueueDepth(depthCore.multiply(askJitter).setScale(0, RoundingMode.HALF_UP));
    }

    private void jitterVolatility(SimulatedMarket market, ThreadLocalRandom rng) {
        if (rng.nextInt(10) == 0) {
            BigDecimal change = BigDecimal.valueOf(rng.nextDouble(-0.005, 0.005));
            BigDecimal newVol = market.getVolatility().add(change)
                    .max(new BigDecimal("0.005"))
                    .min(new BigDecimal("0.20"));
            market.setVolatility(newVol.setScale(4, RoundingMode.HALF_UP));
        }
    }

    private void jitterRewardAndCompetition(SimulatedMarket market, ThreadLocalRandom rng) {
        if (rng.nextInt(20) == 0) {
            BigDecimal rewardChange = BigDecimal.valueOf(rng.nextDouble(-0.3, 0.3));
            market.setRewardScore(market.getRewardScore().add(rewardChange)
                    .max(BigDecimal.ONE).min(BigDecimal.TEN)
                    .setScale(2, RoundingMode.HALF_UP));
        }
        if (rng.nextInt(15) == 0) {
            BigDecimal compChange = BigDecimal.valueOf(rng.nextDouble(-0.1, 0.1));
            market.setCompetitionLevel(market.getCompetitionLevel().add(compChange)
                    .max(BigDecimal.ZERO).min(BigDecimal.ONE)
                    .setScale(2, RoundingMode.HALF_UP));
        }
    }

    public void applyPostFillImpact(SimulatedMarket market, BigDecimal impactDirection, BigDecimal impactSize) {
        BigDecimal impact = impactDirection.multiply(impactSize).multiply(TICK_SIZE);
        BigDecimal newMid = market.getMidPrice().add(impact)
                .max(MIN_PRICE).min(MAX_PRICE)
                .setScale(2, RoundingMode.HALF_UP);
        market.setMidPrice(newMid);
    }
}
