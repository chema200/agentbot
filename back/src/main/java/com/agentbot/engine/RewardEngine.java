package com.agentbot.engine;

import com.agentbot.engine.model.EngineOrder;
import com.agentbot.engine.model.SimulatedMarket;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;

@Slf4j
@Component
@RequiredArgsConstructor
public class RewardEngine {

    private static final BigDecimal TICK = new BigDecimal("0.01");
    private static final BigDecimal MAX_ELIGIBLE_DISTANCE = TICK.multiply(new BigDecimal("3"));
    private static final double DECAY_K = 150.0;
    private static final BigDecimal CROWDING_SCALE = new BigDecimal("5000");
    private static final BigDecimal BASE_POOL_PER_REWARD_POINT = new BigDecimal("0.15");

    private final OrderManager orderManager;

    @Getter
    private BigDecimal totalRewardsPaid = BigDecimal.ZERO;

    private final Map<String, BigDecimal> rewardsByMarket = new ConcurrentHashMap<>();
    private final Map<String, BigDecimal> capitalUsedByMarket = new ConcurrentHashMap<>();
    @Getter
    private final Map<String, RewardMetrics> metricsByMarket = new ConcurrentHashMap<>();
    @Getter
    private long totalEligibleOrders = 0;
    @Getter
    private long totalIneligibleOrders = 0;

    public BigDecimal distributeRewards(SimulatedMarket market) {
        BigDecimal rewardPool = calculateRewardPool(market);
        if (rewardPool.compareTo(BigDecimal.ZERO) <= 0) return BigDecimal.ZERO;

        List<EngineOrder> ourOrders = orderManager.getActiveOrdersForMarket(market.getMarketId())
                .stream()
                .filter(EngineOrder::isVisible)
                .toList();

        if (ourOrders.isEmpty()) return BigDecimal.ZERO;

        BigDecimal ourScore = BigDecimal.ZERO;
        BigDecimal ourCapital = BigDecimal.ZERO;
        int eligible = 0;
        int ineligible = 0;

        for (EngineOrder order : ourOrders) {
            BigDecimal score = calculateOrderScore(order, market);
            if (score.compareTo(BigDecimal.ZERO) > 0) {
                ourScore = ourScore.add(score);
                ourCapital = ourCapital.add(order.getRemainingSize().multiply(order.getPrice()));
                eligible++;
            } else {
                ineligible++;
            }
        }

        totalEligibleOrders += eligible;
        totalIneligibleOrders += ineligible;

        if (ourScore.compareTo(BigDecimal.ZERO) <= 0) return BigDecimal.ZERO;

        BigDecimal competitorScore = simulateCompetitorScore(market);
        BigDecimal totalScore = ourScore.add(competitorScore);

        BigDecimal effectivePool = applyCrowdingEffect(rewardPool, totalScore);

        BigDecimal ourShare = ourScore.divide(totalScore, 10, RoundingMode.HALF_UP);
        BigDecimal reward = effectivePool.multiply(ourShare).setScale(6, RoundingMode.HALF_UP);

        totalRewardsPaid = totalRewardsPaid.add(reward);
        rewardsByMarket.merge(market.getMarketId(), reward, BigDecimal::add);
        capitalUsedByMarket.merge(market.getMarketId(), ourCapital, (a, b) -> b);

        RewardMetrics metrics = metricsByMarket.computeIfAbsent(
                market.getMarketId(), k -> new RewardMetrics());
        metrics.cycleCount.incrementAndGet();
        metrics.totalReward = metrics.totalReward.add(reward);
        metrics.lastShare = ourShare;
        metrics.lastPool = effectivePool;
        metrics.lastCompetitorScore = competitorScore;
        metrics.lastOurScore = ourScore;
        metrics.eligibleOrders += eligible;
        metrics.ineligibleOrders += ineligible;

        if (reward.compareTo(new BigDecimal("0.001")) > 0) {
            log.debug("Reward: {} on {} (share: {}%, pool: {} -> {}, our: {}, comp: {}, elig: {}/{})",
                    reward.setScale(4, RoundingMode.HALF_UP), market.getMarketId(),
                    ourShare.multiply(new BigDecimal("100")).setScale(2, RoundingMode.HALF_UP),
                    rewardPool.setScale(4, RoundingMode.HALF_UP),
                    effectivePool.setScale(4, RoundingMode.HALF_UP),
                    ourScore.setScale(2, RoundingMode.HALF_UP),
                    competitorScore.setScale(2, RoundingMode.HALF_UP),
                    eligible, eligible + ineligible);
        }

        return reward;
    }

    BigDecimal calculateOrderScore(EngineOrder order, SimulatedMarket market) {
        BigDecimal mid = market.getMidPrice();
        BigDecimal distance;
        if (order.getSide() == EngineOrder.Side.BUY) {
            distance = mid.subtract(order.getPrice());
        } else {
            distance = order.getPrice().subtract(mid);
        }

        if (distance.compareTo(BigDecimal.ZERO) < 0) {
            distance = distance.abs();
        }

        if (distance.compareTo(MAX_ELIGIBLE_DISTANCE) > 0) {
            return BigDecimal.ZERO;
        }

        BigDecimal bestPrice;
        if (order.getSide() == EngineOrder.Side.BUY) {
            bestPrice = market.getBestBid();
        } else {
            bestPrice = market.getBestAsk();
        }
        BigDecimal distFromBest = order.getPrice().subtract(bestPrice).abs();
        if (distFromBest.compareTo(TICK.multiply(new BigDecimal("4"))) > 0) {
            return BigDecimal.ZERO;
        }

        double distDouble = distance.doubleValue();
        double decayFactor = Math.exp(-DECAY_K * distDouble);

        BigDecimal sizeComponent = order.getRemainingSize();

        BigDecimal queuePenalty = BigDecimal.ONE.divide(
                BigDecimal.ONE.add(order.getQueueAhead().divide(new BigDecimal("100"), 6, RoundingMode.HALF_UP)),
                6, RoundingMode.HALF_UP);

        BigDecimal timeWeight = calculateTimeWeight(order);

        BigDecimal score = sizeComponent
                .multiply(BigDecimal.valueOf(decayFactor))
                .multiply(queuePenalty)
                .multiply(timeWeight)
                .setScale(6, RoundingMode.HALF_UP);

        return score.max(BigDecimal.ZERO);
    }

    private BigDecimal calculateTimeWeight(EngineOrder order) {
        long secondsInBook = Duration.between(order.getCreatedAt(), Instant.now()).toSeconds();

        if (secondsInBook <= 0) return new BigDecimal("0.5");

        double logWeight = 1.0 + Math.log(1.0 + secondsInBook) * 0.15;
        double capped = Math.min(1.5, logWeight);

        if (secondsInBook > 15) {
            double stalePenalty = 1.0 - ((secondsInBook - 15.0) / 60.0) * 0.3;
            capped *= Math.max(0.5, stalePenalty);
        }

        return BigDecimal.valueOf(capped).setScale(4, RoundingMode.HALF_UP);
    }

    private BigDecimal calculateRewardPool(SimulatedMarket market) {
        BigDecimal baseReward = market.getRewardScore().multiply(BASE_POOL_PER_REWARD_POINT);

        BigDecimal volumeBonus = market.getTickVolume()
                .divide(new BigDecimal("1000"), 4, RoundingMode.HALF_UP)
                .min(new BigDecimal("0.2"));
        baseReward = baseReward.add(volumeBonus);

        BigDecimal regimeMultiplier = switch (market.getRegime()) {
            case CALM -> BigDecimal.ONE;
            case NORMAL -> new BigDecimal("0.8");
            case VOLATILE -> new BigDecimal("0.3");
            case CRISIS -> new BigDecimal("0.05");
        };

        return baseReward.multiply(regimeMultiplier).setScale(6, RoundingMode.HALF_UP);
    }

    private BigDecimal applyCrowdingEffect(BigDecimal basePool, BigDecimal totalScore) {
        BigDecimal crowdingDivisor = BigDecimal.ONE.add(
                totalScore.divide(CROWDING_SCALE, 8, RoundingMode.HALF_UP));
        return basePool.divide(crowdingDivisor, 8, RoundingMode.HALF_UP);
    }

    private BigDecimal simulateCompetitorScore(SimulatedMarket market) {
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        BigDecimal totalCompScore = BigDecimal.ZERO;
        double competition = market.getCompetitionLevel().doubleValue();

        int baseCompetitors = 6;
        int scaledCompetitors = (int) (baseCompetitors + competition * 14);

        int topOfBookCount = (int) (scaledCompetitors * 0.5);
        for (int i = 0; i < topOfBookCount; i++) {
            double distTicks = rng.nextDouble(0.3, 1.5);
            BigDecimal distance = BigDecimal.valueOf(distTicks).multiply(TICK);
            BigDecimal size = BigDecimal.valueOf(rng.nextInt(40, 200));
            double decay = Math.exp(-DECAY_K * distance.doubleValue());
            BigDecimal queuePen = BigDecimal.valueOf(rng.nextDouble(0.5, 1.0));
            BigDecimal timeW = BigDecimal.valueOf(rng.nextDouble(0.9, 1.4));
            BigDecimal score = size.multiply(BigDecimal.valueOf(decay))
                    .multiply(queuePen).multiply(timeW);
            totalCompScore = totalCompScore.add(score);
        }

        int nearBookCount = (int) (scaledCompetitors * 0.35);
        for (int i = 0; i < nearBookCount; i++) {
            double distTicks = rng.nextDouble(1.5, 3.0);
            BigDecimal distance = BigDecimal.valueOf(distTicks).multiply(TICK);
            BigDecimal size = BigDecimal.valueOf(rng.nextInt(30, 120));
            double decay = Math.exp(-DECAY_K * distance.doubleValue());
            BigDecimal timeW = BigDecimal.valueOf(rng.nextDouble(0.8, 1.3));
            BigDecimal score = size.multiply(BigDecimal.valueOf(decay)).multiply(timeW);
            totalCompScore = totalCompScore.add(score);
        }

        int farCount = scaledCompetitors - topOfBookCount - nearBookCount;
        for (int i = 0; i < farCount; i++) {
            double distTicks = rng.nextDouble(3.0, 6.0);
            BigDecimal distance = BigDecimal.valueOf(distTicks).multiply(TICK);
            BigDecimal size = BigDecimal.valueOf(rng.nextInt(20, 80));
            double decay = Math.exp(-DECAY_K * distance.doubleValue());
            BigDecimal score = size.multiply(BigDecimal.valueOf(decay));
            totalCompScore = totalCompScore.add(score);
        }

        BigDecimal intensityScale = BigDecimal.ONE.add(
                market.getCompetitionLevel().multiply(new BigDecimal("3")));
        return totalCompScore.multiply(intensityScale).setScale(4, RoundingMode.HALF_UP);
    }

    public BigDecimal getRewardsForMarket(String marketId) {
        return rewardsByMarket.getOrDefault(marketId, BigDecimal.ZERO);
    }

    public Map<String, BigDecimal> getAllMarketRewards() {
        return Map.copyOf(rewardsByMarket);
    }

    public BigDecimal getRewardEfficiency(String marketId) {
        BigDecimal rewards = rewardsByMarket.getOrDefault(marketId, BigDecimal.ZERO);
        BigDecimal capital = capitalUsedByMarket.getOrDefault(marketId, BigDecimal.ZERO);
        if (capital.compareTo(BigDecimal.ZERO) <= 0) return BigDecimal.ZERO;
        return rewards.divide(capital, 8, RoundingMode.HALF_UP);
    }

    public static class RewardMetrics {
        public final AtomicLong cycleCount = new AtomicLong(0);
        public BigDecimal totalReward = BigDecimal.ZERO;
        public BigDecimal lastShare = BigDecimal.ZERO;
        public BigDecimal lastPool = BigDecimal.ZERO;
        public BigDecimal lastCompetitorScore = BigDecimal.ZERO;
        public BigDecimal lastOurScore = BigDecimal.ZERO;
        public long eligibleOrders = 0;
        public long ineligibleOrders = 0;

        public double getEligibilityRate() {
            long total = eligibleOrders + ineligibleOrders;
            if (total == 0) return 0.0;
            return (double) eligibleOrders / total;
        }
    }

    public void reset() {
        totalRewardsPaid = BigDecimal.ZERO;
        rewardsByMarket.clear();
        capitalUsedByMarket.clear();
        metricsByMarket.clear();
        totalEligibleOrders = 0;
        totalIneligibleOrders = 0;
    }
}
