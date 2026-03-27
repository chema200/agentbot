package com.agentbot.engine;

import com.agentbot.engine.model.MarketScore;
import com.agentbot.engine.model.SimulatedMarket;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

@Slf4j
@Component
@RequiredArgsConstructor
public class MarketRankingEngine {

    private static final BigDecimal MIN_EDGE_SCORE = new BigDecimal("0.3");
    private static final BigDecimal MAX_VOLATILITY = new BigDecimal("0.08");
    private static final BigDecimal MIN_SPREAD = new BigDecimal("0.012");
    private static final BigDecimal HYSTERESIS_EDGE_BONUS = new BigDecimal("0.3");
    private static final int SMOOTHING_WINDOW = 5;
    private static final int MIN_HOLD_CYCLES = 8;
    private static final int RE_EVAL_INTERVAL = 6;
    private static final double DECAY_K = 150.0;

    private final PerformanceTracker performanceTracker;

    @Getter
    private final Map<String, MarketEdgeHistory> edgeHistory = new ConcurrentHashMap<>();

    @Getter
    private Set<String> activeMarketIds = ConcurrentHashMap.newKeySet();

    private final AtomicLong evalCycle = new AtomicLong(0);
    private List<MarketScore> lastSelectedResult = List.of();

    public List<MarketScore> rankMarkets(List<SimulatedMarket> markets) {
        AtomicInteger rankCounter = new AtomicInteger(1);

        return markets.stream()
                .map(this::scoreMarket)
                .sorted(Comparator.comparing(MarketScore::getEdgeScore).reversed())
                .peek(ms -> ms.setRank(rankCounter.getAndIncrement()))
                .collect(Collectors.toList());
    }

    public List<MarketScore> getTopMarkets(List<SimulatedMarket> markets, int topN) {
        return selectMarkets(markets, topN);
    }

    /**
     * Scores all markets, applies eligibility filters, selects up to maxMarkets.
     * Returns only selected markets. Full results stored in latestFullRanking.
     */
    @Getter
    private List<MarketScore> latestFullRanking = List.of();

    public List<MarketScore> selectMarkets(List<SimulatedMarket> allMarkets, int maxMarkets) {
        long cycle = evalCycle.incrementAndGet();

        List<MarketScore> scored = rankMarkets(allMarkets);
        latestFullRanking = scored;

        boolean shouldSkip = !activeMarketIds.isEmpty()
                && !lastSelectedResult.isEmpty()
                && cycle % RE_EVAL_INTERVAL != 0;
        if (shouldSkip) {
            for (MarketScore ms : scored) {
                ms.setSelected(activeMarketIds.contains(ms.getMarketId()));
                if (!ms.isSelected()) ms.setRejectionReason("between_evals");
                updateEdgeHistory(ms);
            }
            return lastSelectedResult;
        }

        Map<String, SimulatedMarket> marketMap = allMarkets.stream()
                .collect(Collectors.toMap(SimulatedMarket::getMarketId, m -> m));

        List<MarketScore> selected = new ArrayList<>();

        for (MarketScore ms : scored) {
            SimulatedMarket market = marketMap.get(ms.getMarketId());
            if (market == null) {
                ms.setSelected(false);
                ms.setRejectionReason("not_found");
                continue;
            }

            if (selected.size() >= maxMarkets) {
                ms.setSelected(false);
                ms.setRejectionReason("max_slots_full");
                continue;
            }

            String reason = evaluateEligibility(ms, market);
            if (reason != null) {
                ms.setSelected(false);
                ms.setRejectionReason(reason);
                continue;
            }

            ms.setSelected(true);
            selected.add(ms);
        }

        Set<String> newActiveIds = selected.stream()
                .map(MarketScore::getMarketId)
                .collect(Collectors.toSet());

        Set<String> exited = new HashSet<>(activeMarketIds);
        exited.removeAll(newActiveIds);

        Set<String> entered = new HashSet<>(newActiveIds);
        entered.removeAll(activeMarketIds);

        if (!exited.isEmpty() || !entered.isEmpty()) {
            log.info("Market switching: entered={} exited={} active={}",
                    entered, exited, newActiveIds.size());
        }

        activeMarketIds = newActiveIds;

        for (MarketScore ms : scored) {
            updateEdgeHistory(ms);
        }

        lastSelectedResult = selected;
        return selected;
    }

    private String evaluateEligibility(MarketScore ms, SimulatedMarket market) {
        if (market.isCrisis()) return "crisis_regime";
        if (market.isInformedFlowActive()) return "informed_flow";

        boolean isCurrentlyActive = activeMarketIds.contains(ms.getMarketId());
        MarketEdgeHistory hist = edgeHistory.get(ms.getMarketId());

        if (isCurrentlyActive && hist != null && hist.consecutiveActive < MIN_HOLD_CYCLES) {
            return null;
        }

        BigDecimal effectiveEdge = ms.getEdgeScore();
        if (hist != null && hist.recentEdges.size() >= 2) {
            effectiveEdge = hist.getSmoothedEdge(SMOOTHING_WINDOW);
        }

        BigDecimal effectiveMinEdge = isCurrentlyActive
                ? MIN_EDGE_SCORE.subtract(HYSTERESIS_EDGE_BONUS)
                : MIN_EDGE_SCORE;

        if (effectiveEdge.compareTo(effectiveMinEdge) < 0) {
            return "low_edge=" + effectiveEdge.setScale(3, RoundingMode.HALF_UP);
        }

        if (market.getVolatility().compareTo(MAX_VOLATILITY) > 0) {
            return "high_vol=" + market.getVolatility();
        }

        if (market.getSpread().compareTo(MIN_SPREAD) < 0) {
            return "tight_spread=" + market.getSpread();
        }

        if (hist != null && hist.samples >= 15) {
            BigDecimal avgEdge = hist.getAverageEdge();
            if (avgEdge.compareTo(MIN_EDGE_SCORE.multiply(new BigDecimal("0.4"))) < 0) {
                return "historically_poor=" + avgEdge.setScale(3, RoundingMode.HALF_UP);
            }
        }

        if (!performanceTracker.isMarketProfitable(ms.getMarketId())
                && performanceTracker.getTotalFills(ms.getMarketId()) > 8) {
            return "unprofitable";
        }

        return null;
    }

    MarketScore scoreMarket(SimulatedMarket market) {
        BigDecimal rewardPool = estimateRewardPool(market);
        CompetitorEstimate competitors = estimateCompetitors(market);

        BigDecimal rewardEfficiency = competitors.totalScore.compareTo(BigDecimal.ZERO) > 0
                ? rewardPool.divide(competitors.totalScore, 8, RoundingMode.HALF_UP)
                : rewardPool;

        BigDecimal competitionDensity = competitors.totalScore.compareTo(BigDecimal.ZERO) > 0
                ? competitors.nearMidScore.divide(competitors.totalScore, 6, RoundingMode.HALF_UP)
                        .min(BigDecimal.ONE)
                : BigDecimal.ZERO;

        BigDecimal compLevel = market.getCompetitionLevel().max(new BigDecimal("0.01"));
        BigDecimal rewardPerCompetition = rewardPool.divide(compLevel, 6, RoundingMode.HALF_UP);

        BigDecimal spreadValue = market.getSpread();
        BigDecimal volPenalty = market.getRealizedVolatility()
                .max(market.getVolatility())
                .multiply(new BigDecimal("10"));

        BigDecimal regimePenalty = switch (market.getRegime()) {
            case CALM -> BigDecimal.ZERO;
            case NORMAL -> new BigDecimal("0.1");
            case VOLATILE -> new BigDecimal("0.5");
            case CRISIS -> new BigDecimal("2.0");
        };

        BigDecimal edgeScore = rewardEfficiency.multiply(new BigDecimal("1000"))
                .add(spreadValue.multiply(new BigDecimal("5")))
                .add(rewardPerCompetition.multiply(new BigDecimal("0.1")))
                .subtract(volPenalty)
                .subtract(regimePenalty)
                .subtract(competitionDensity.multiply(new BigDecimal("0.5")))
                .setScale(4, RoundingMode.HALF_UP);

        return MarketScore.builder()
                .marketId(market.getMarketId())
                .marketName(market.getName())
                .totalScore(edgeScore)
                .edgeScore(edgeScore)
                .rewardEfficiency(rewardEfficiency.setScale(6, RoundingMode.HALF_UP))
                .competitionDensity(competitionDensity.setScale(4, RoundingMode.HALF_UP))
                .rewardPerCompetition(rewardPerCompetition.setScale(4, RoundingMode.HALF_UP))
                .volatilityPenalty(volPenalty.add(regimePenalty).setScale(4, RoundingMode.HALF_UP))
                .rewardComponent(rewardPool.setScale(4, RoundingMode.HALF_UP))
                .spreadComponent(spreadValue.setScale(4, RoundingMode.HALF_UP))
                .competitionComponent(BigDecimal.ONE.subtract(market.getCompetitionLevel()).setScale(4, RoundingMode.HALF_UP))
                .liquidityComponent(market.getLiquidityScore().setScale(4, RoundingMode.HALF_UP))
                .riskComponent(volPenalty.setScale(4, RoundingMode.HALF_UP))
                .build();
    }

    private BigDecimal estimateRewardPool(SimulatedMarket market) {
        BigDecimal base = market.getRewardScore().multiply(new BigDecimal("0.15"));
        BigDecimal regimeMult = switch (market.getRegime()) {
            case CALM -> BigDecimal.ONE;
            case NORMAL -> new BigDecimal("0.8");
            case VOLATILE -> new BigDecimal("0.3");
            case CRISIS -> new BigDecimal("0.05");
        };
        return base.multiply(regimeMult).setScale(6, RoundingMode.HALF_UP);
    }

    private CompetitorEstimate estimateCompetitors(SimulatedMarket market) {
        double comp = market.getCompetitionLevel().doubleValue();
        int numCompetitors = (int) (6 + comp * 14);
        double avgSize = 100.0;
        double scale = 1.0 + comp * 3.0;

        double totalScore = 0;
        double nearMidScore = 0;

        double[] bucketDist = {0.5, 1.0, 1.5, 2.5, 4.0};
        double[] bucketFrac = {0.30, 0.25, 0.20, 0.15, 0.10};

        for (int b = 0; b < bucketDist.length; b++) {
            int count = (int) Math.ceil(numCompetitors * bucketFrac[b]);
            double decay = Math.exp(-DECAY_K * bucketDist[b] * 0.01);
            double bucketScore = count * avgSize * decay;
            totalScore += bucketScore;
            if (bucketDist[b] <= 2.0) {
                nearMidScore += bucketScore;
            }
        }

        totalScore *= scale;
        nearMidScore *= scale;

        return new CompetitorEstimate(
                BigDecimal.valueOf(totalScore).setScale(4, RoundingMode.HALF_UP),
                BigDecimal.valueOf(nearMidScore).setScale(4, RoundingMode.HALF_UP));
    }

    private void updateEdgeHistory(MarketScore ms) {
        MarketEdgeHistory hist = edgeHistory.computeIfAbsent(
                ms.getMarketId(), k -> new MarketEdgeHistory());
        hist.totalEdge = hist.totalEdge.add(ms.getEdgeScore());
        hist.samples++;
        hist.lastEdge = ms.getEdgeScore();
        hist.recordEdge(ms.getEdgeScore(), ms.getCompetitionDensity());
        if (ms.isSelected()) {
            hist.consecutiveActive++;
        } else {
            hist.consecutiveActive = 0;
        }
        hist.lastSelected = ms.isSelected();
    }

    public Set<String> getExitedMarkets(Set<String> previousActive, Set<String> currentActive) {
        Set<String> exited = new HashSet<>(previousActive);
        exited.removeAll(currentActive);
        return exited;
    }

    private record CompetitorEstimate(BigDecimal totalScore, BigDecimal nearMidScore) {}

    public static class MarketEdgeHistory {
        public BigDecimal totalEdge = BigDecimal.ZERO;
        public long samples = 0;
        public BigDecimal lastEdge = BigDecimal.ZERO;
        public boolean lastSelected = false;
        public int consecutiveActive = 0;
        public final Deque<BigDecimal> recentEdges = new ArrayDeque<>();
        public final Deque<BigDecimal> recentDensity = new ArrayDeque<>();

        public BigDecimal getAverageEdge() {
            if (samples == 0) return BigDecimal.ZERO;
            return totalEdge.divide(BigDecimal.valueOf(samples), 4, RoundingMode.HALF_UP);
        }

        public BigDecimal getSmoothedEdge(int window) {
            if (recentEdges.isEmpty()) return lastEdge;
            BigDecimal sum = BigDecimal.ZERO;
            int count = 0;
            for (BigDecimal e : recentEdges) {
                sum = sum.add(e);
                count++;
                if (count >= window) break;
            }
            return sum.divide(BigDecimal.valueOf(count), 4, RoundingMode.HALF_UP);
        }

        public BigDecimal getSmoothedDensity(int window) {
            if (recentDensity.isEmpty()) return BigDecimal.ONE;
            BigDecimal sum = BigDecimal.ZERO;
            int count = 0;
            for (BigDecimal d : recentDensity) {
                sum = sum.add(d);
                count++;
                if (count >= window) break;
            }
            return sum.divide(BigDecimal.valueOf(count), 4, RoundingMode.HALF_UP);
        }

        public void recordEdge(BigDecimal edge, BigDecimal density) {
            recentEdges.addFirst(edge);
            if (recentEdges.size() > 10) recentEdges.removeLast();
            recentDensity.addFirst(density);
            if (recentDensity.size() > 10) recentDensity.removeLast();
        }
    }

    public void reset() {
        edgeHistory.clear();
        activeMarketIds = ConcurrentHashMap.newKeySet();
        evalCycle.set(0);
        lastSelectedResult = List.of();
        latestFullRanking = List.of();
    }
}
