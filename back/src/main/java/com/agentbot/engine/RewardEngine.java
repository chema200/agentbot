package com.agentbot.engine;

import com.agentbot.engine.model.EngineOrder;
import com.agentbot.engine.model.SimulatedMarket;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

@Slf4j
@Component
@RequiredArgsConstructor
public class RewardEngine {

    private static final BigDecimal MIN_DISTANCE = new BigDecimal("0.001");
    private static final BigDecimal MIN_TIME_WEIGHT = new BigDecimal("0.1");
    private static final BigDecimal MAX_TIME_WEIGHT = new BigDecimal("2.0");
    private static final BigDecimal TIME_SCALE_SECONDS = new BigDecimal("10");
    private static final int NUM_COMPETITOR_ORDERS = 8;

    private final OrderManager orderManager;

    @Getter
    private BigDecimal totalRewardsPaid = BigDecimal.ZERO;

    private final Map<String, BigDecimal> rewardsByMarket = new ConcurrentHashMap<>();

    public BigDecimal distributeRewards(SimulatedMarket market) {
        BigDecimal rewardPool = calculateRewardPool(market);
        if (rewardPool.compareTo(BigDecimal.ZERO) <= 0) return BigDecimal.ZERO;

        List<EngineOrder> ourOrders = orderManager.getActiveOrdersForMarket(market.getMarketId())
                .stream()
                .filter(EngineOrder::isVisible)
                .toList();

        if (ourOrders.isEmpty()) return BigDecimal.ZERO;

        BigDecimal ourScore = BigDecimal.ZERO;
        for (EngineOrder order : ourOrders) {
            ourScore = ourScore.add(calculateOrderScore(order, market));
        }

        BigDecimal competitorScore = simulateCompetitorScore(market);

        BigDecimal totalScore = ourScore.add(competitorScore);
        if (totalScore.compareTo(BigDecimal.ZERO) <= 0) return BigDecimal.ZERO;

        BigDecimal ourShare = ourScore.divide(totalScore, 8, RoundingMode.HALF_UP);
        BigDecimal reward = rewardPool.multiply(ourShare).setScale(6, RoundingMode.HALF_UP);

        totalRewardsPaid = totalRewardsPaid.add(reward);
        rewardsByMarket.merge(market.getMarketId(), reward, BigDecimal::add);

        if (reward.compareTo(new BigDecimal("0.01")) > 0) {
            log.debug("Reward: {} on {} (share: {}%, pool: {}, ourScore: {}, compScore: {})",
                    reward, market.getMarketId(),
                    ourShare.multiply(new BigDecimal("100")).setScale(1, RoundingMode.HALF_UP),
                    rewardPool, ourScore.setScale(2, RoundingMode.HALF_UP),
                    competitorScore.setScale(2, RoundingMode.HALF_UP));
        }

        return reward;
    }

    BigDecimal calculateOrderScore(EngineOrder order, SimulatedMarket market) {
        BigDecimal distance;
        if (order.getSide() == EngineOrder.Side.BUY) {
            distance = market.getMidPrice().subtract(order.getPrice()).abs();
        } else {
            distance = order.getPrice().subtract(market.getMidPrice()).abs();
        }
        distance = distance.max(MIN_DISTANCE);

        BigDecimal sizeScore = order.getRemainingSize().divide(distance, 6, RoundingMode.HALF_UP);

        BigDecimal timeWeight = calculateTimeWeight(order);

        return sizeScore.multiply(timeWeight).setScale(6, RoundingMode.HALF_UP);
    }

    private BigDecimal calculateTimeWeight(EngineOrder order) {
        long secondsInBook = Duration.between(order.getCreatedAt(), Instant.now()).toSeconds();
        BigDecimal timeComponent = BigDecimal.valueOf(secondsInBook)
                .divide(TIME_SCALE_SECONDS, 6, RoundingMode.HALF_UP);
        BigDecimal weight = BigDecimal.ONE.add(
                timeComponent.multiply(new BigDecimal("0.1"))
        );
        return weight.max(MIN_TIME_WEIGHT).min(MAX_TIME_WEIGHT);
    }

    private BigDecimal calculateRewardPool(SimulatedMarket market) {
        BigDecimal baseReward = market.getRewardScore().multiply(new BigDecimal("0.5"));

        BigDecimal volumeBonus = market.getTickVolume()
                .divide(new BigDecimal("500"), 4, RoundingMode.HALF_UP)
                .min(new BigDecimal("0.5"));
        baseReward = baseReward.add(volumeBonus);

        BigDecimal regimeMultiplier = switch (market.getRegime()) {
            case CALM -> new BigDecimal("1.2");
            case NORMAL -> BigDecimal.ONE;
            case VOLATILE -> new BigDecimal("0.7");
            case CRISIS -> new BigDecimal("0.3");
        };

        return baseReward.multiply(regimeMultiplier).setScale(4, RoundingMode.HALF_UP);
    }

    private BigDecimal simulateCompetitorScore(SimulatedMarket market) {
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        BigDecimal totalCompScore = BigDecimal.ZERO;
        double competition = market.getCompetitionLevel().doubleValue();

        int numCompetitors = (int) (NUM_COMPETITOR_ORDERS * (0.5 + competition));
        BigDecimal mid = market.getMidPrice();

        for (int i = 0; i < numCompetitors; i++) {
            double distTicks = rng.nextDouble(1, 6);
            BigDecimal distance = new BigDecimal(distTicks).multiply(new BigDecimal("0.01"))
                    .max(MIN_DISTANCE);

            BigDecimal size = BigDecimal.valueOf(rng.nextInt(20, 150));

            BigDecimal timeW = BigDecimal.valueOf(rng.nextDouble(0.8, 1.8));

            BigDecimal score = size.divide(distance, 6, RoundingMode.HALF_UP)
                    .multiply(timeW);
            totalCompScore = totalCompScore.add(score);
        }

        BigDecimal competitionScale = BigDecimal.ONE.add(
                market.getCompetitionLevel().multiply(new BigDecimal("2"))
        );
        return totalCompScore.multiply(competitionScale).setScale(4, RoundingMode.HALF_UP);
    }

    public BigDecimal getRewardsForMarket(String marketId) {
        return rewardsByMarket.getOrDefault(marketId, BigDecimal.ZERO);
    }

    public Map<String, BigDecimal> getAllMarketRewards() {
        return Map.copyOf(rewardsByMarket);
    }
}
