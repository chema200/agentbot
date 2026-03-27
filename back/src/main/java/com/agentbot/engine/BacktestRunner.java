package com.agentbot.engine;

import com.agentbot.engine.model.*;
import com.agentbot.model.BacktestResultDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;

@Slf4j
@Component
@RequiredArgsConstructor
public class BacktestRunner {

    private static final int TOP_MARKETS = 5;

    private final MarketScanner marketScanner;
    private final MarketRankingEngine rankingEngine;
    private final StrategyEngine strategyEngine;
    private final OrderManager orderManager;
    private final QuoteSupervisor quoteSupervisor;
    private final InventoryManager inventoryManager;
    private final RiskManager riskManager;
    private final PnLService pnlService;
    private final PerformanceTracker performanceTracker;
    private final RewardEngine rewardEngine;

    public BacktestResultDto run(int cycles, long seed, StressProfile profile) {
        long startMs = System.currentTimeMillis();
        String runId = UUID.randomUUID().toString().substring(0, 12);

        resetAllState();
        applyStressProfile(profile);

        int fillIndex = 0;
        BigDecimal peakPnl = BigDecimal.ZERO;
        BigDecimal maxDrawdown = BigDecimal.ZERO;
        BigDecimal maxExposure = BigDecimal.ZERO;
        Set<String> previousActive = Set.of();

        for (int c = 0; c < cycles; c++) {
            marketScanner.tickAll();

            List<MarketScore> selected = rankingEngine.getTopMarkets(
                    marketScanner.getAllMarkets(), TOP_MARKETS);

            Set<String> currentActive = rankingEngine.getActiveMarketIds();
            Set<String> exited = new HashSet<>(previousActive);
            exited.removeAll(currentActive);
            for (String exitedId : exited) {
                orderManager.getActiveOrdersForMarket(exitedId)
                        .forEach(o -> orderManager.cancelOrder(o.getOrderId()));
            }
            previousActive = Set.copyOf(currentActive);

            List<EngineFill> allFills = quoteSupervisor.getFills();
            int currentSize = allFills.size();
            for (int i = fillIndex; i < currentSize; i++) {
                EngineFill fill = allFills.get(i);
                inventoryManager.processFill(fill);
                pnlService.recordFill(fill);
            }
            fillIndex = currentSize;

            riskManager.evaluateGlobalRisk(inventoryManager.getGlobalNetExposure());

            BigDecimal totalEdge = selected.stream()
                    .map(MarketScore::getEdgeScore)
                    .filter(e -> e.compareTo(BigDecimal.ZERO) > 0)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);

            for (MarketScore scored : selected) {
                SimulatedMarket market = marketScanner.getMarket(scored.getMarketId());
                if (market == null) continue;

                quoteSupervisor.supervise(market);

                InventoryPosition position = inventoryManager.getPosition(market.getMarketId());
                int activeForMarket = orderManager.activeOrderCountForMarket(market.getMarketId());
                int totalActive = orderManager.activeOrderCount();
                boolean allowed = riskManager.canTrade(market, position, activeForMarket, totalActive);

                BigDecimal capitalShare = totalEdge.compareTo(BigDecimal.ZERO) > 0
                        ? scored.getEdgeScore().max(BigDecimal.ZERO)
                            .divide(totalEdge, 6, RoundingMode.HALF_UP)
                        : new BigDecimal("0.2");

                strategyEngine.executeStrategy(market, position, allowed,
                        riskManager.getMaxOrdersPerSide(), scored, capitalShare);

                BigDecimal reward = rewardEngine.distributeRewards(market);
                if (reward.compareTo(BigDecimal.ZERO) > 0) {
                    pnlService.recordReward(market.getMarketId(), reward);
                }
            }

            BigDecimal currentPnl = pnlService.getTotalPnl();
            if (currentPnl.compareTo(peakPnl) > 0) {
                peakPnl = currentPnl;
            }
            BigDecimal drawdown = peakPnl.subtract(currentPnl);
            if (drawdown.compareTo(maxDrawdown) > 0) {
                maxDrawdown = drawdown;
            }

            BigDecimal exposure = inventoryManager.getGlobalNetExposure();
            if (exposure.compareTo(maxExposure) > 0) {
                maxExposure = exposure;
            }
        }

        long elapsedMs = System.currentTimeMillis() - startMs;
        return collectResults(runId, seed, profile, cycles, elapsedMs, maxDrawdown, maxExposure);
    }

    private BacktestResultDto collectResults(String runId, long seed, StressProfile profile,
                                              int cycles, long elapsedMs,
                                              BigDecimal maxDrawdown, BigDecimal maxExposure) {
        List<EngineFill> fills = quoteSupervisor.getFills();
        long toxicCount = fills.stream().filter(EngineFill::isToxicFlow).count();
        long positiveFills = fills.stream()
                .filter(f -> f.getSlippage().compareTo(BigDecimal.ZERO) <= 0)
                .count();
        double winRate = fills.isEmpty() ? 0.0 : (double) positiveFills / fills.size();
        double avgPpf = fills.isEmpty() ? 0.0
                : pnlService.getTradingPnl().doubleValue() / fills.size();

        double adverseRate = 0.0;
        var allPerf = performanceTracker.getAllPerformance();
        int totalAdverse = 0, totalF = 0;
        for (var perf : allPerf.values()) {
            totalAdverse += perf.adverseFills.get();
            totalF += perf.totalFills.get();
        }
        if (totalF > 0) adverseRate = (double) totalAdverse / totalF;

        return BacktestResultDto.builder()
                .runId(runId)
                .seed(seed)
                .stressProfile(profile.name())
                .cycles(cycles)
                .simulatedDurationSec(cycles * 2)
                .totalPnl(pnlService.getTotalPnl().setScale(4, RoundingMode.HALF_UP))
                .tradingPnl(pnlService.getTradingPnl().setScale(4, RoundingMode.HALF_UP))
                .rewardPnl(pnlService.getTotalRewardPnl().setScale(4, RoundingMode.HALF_UP))
                .totalFills(fills.size())
                .toxicFills((int) toxicCount)
                .totalFees(pnlService.getTotalFees().setScale(4, RoundingMode.HALF_UP))
                .maxExposure(maxExposure.setScale(2, RoundingMode.HALF_UP))
                .maxDrawdown(maxDrawdown.setScale(4, RoundingMode.HALF_UP))
                .finalInventoryNet(inventoryManager.getGlobalNetExposure().setScale(2, RoundingMode.HALF_UP))
                .avgProfitPerFill(BigDecimal.valueOf(avgPpf).setScale(4, RoundingMode.HALF_UP))
                .adverseSelectionRate(BigDecimal.valueOf(adverseRate).setScale(4, RoundingMode.HALF_UP))
                .winRate(BigDecimal.valueOf(winRate).setScale(4, RoundingMode.HALF_UP))
                .activeMarkets(rankingEngine.getActiveMarketIds().size())
                .elapsedMs(elapsedMs)
                .build();
    }

    private void applyStressProfile(StressProfile profile) {
        for (SimulatedMarket m : marketScanner.getAllMarkets()) {
            m.setCompetitionLevel(profile.getCompetitionLevelBD());
            m.setRewardScore(profile.getRewardScoreBD());
            m.setVolatility(profile.getBaseVolatilityBD());
        }
    }

    private void resetAllState() {
        orderManager.reset();
        orderManager.setBacktestMode(true);
        quoteSupervisor.reset();
        pnlService.reset();
        inventoryManager.reset();
        performanceTracker.reset();
        rewardEngine.reset();
        rankingEngine.reset();
        marketScanner.resetMarkets();
    }
}
