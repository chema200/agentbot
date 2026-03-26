package com.agentbot.engine;

import com.agentbot.engine.model.*;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class TradingEngine {

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

    @Getter
    private TradingEngineState state = TradingEngineState.STOPPED;

    @Getter
    private long cycleCount = 0;

    @Getter
    private List<MarketScore> latestRankings = List.of();

    private int lastProcessedFillIndex = 0;

    public void start() {
        if (state == TradingEngineState.RUNNING) return;
        state = TradingEngineState.RUNNING;
        log.info("Trading engine STARTED");
    }

    public void pause() {
        if (state != TradingEngineState.RUNNING) return;
        state = TradingEngineState.PAUSED;
        log.info("Trading engine PAUSED");
    }

    public void stop() {
        state = TradingEngineState.STOPPED;
        cancelAllOrders();
        log.info("Trading engine STOPPED");
    }

    @Scheduled(fixedDelay = 2000)
    public void engineLoop() {
        if (state != TradingEngineState.RUNNING) return;

        try {
            runCycle();
            cycleCount++;
        } catch (Exception e) {
            log.error("Engine cycle error", e);
            state = TradingEngineState.ERROR;
        }
    }

    private void runCycle() {
        marketScanner.tickAll();

        latestRankings = rankingEngine.getTopMarkets(marketScanner.getAllMarkets(), TOP_MARKETS);

        processNewFills();

        riskManager.evaluateGlobalRisk(inventoryManager.getGlobalNetExposure());

        for (MarketScore scored : latestRankings) {
            SimulatedMarket market = marketScanner.getMarket(scored.getMarketId());
            if (market == null) continue;

            quoteSupervisor.supervise(market);

            InventoryPosition position = inventoryManager.getPosition(market.getMarketId());
            int activeForMarket = orderManager.activeOrderCountForMarket(market.getMarketId());
            int totalActive = orderManager.activeOrderCount();

            boolean allowed = riskManager.canTrade(market, position, activeForMarket, totalActive);

            strategyEngine.executeStrategy(market, position, allowed, riskManager.getMaxOrdersPerSide());
        }

        if (cycleCount % 15 == 0) {
            logSnapshot();
        }
    }

    private void processNewFills() {
        List<EngineFill> allFills = quoteSupervisor.getFills();
        int currentSize = allFills.size();
        for (int i = lastProcessedFillIndex; i < currentSize; i++) {
            EngineFill fill = allFills.get(i);
            inventoryManager.processFill(fill);
            pnlService.recordFill(fill);
        }
        lastProcessedFillIndex = currentSize;
    }

    private void cancelAllOrders() {
        orderManager.getActiveOrders().forEach(o -> orderManager.cancelOrder(o.getOrderId()));
    }

    private void logSnapshot() {
        long totalFills = quoteSupervisor.getFills().size();
        long toxicFills = quoteSupervisor.getFills().stream().filter(f -> f.isToxicFlow()).count();

        log.info("=== Cycle #{} ===", cycleCount);
        log.info("  Orders: {} active | Fills: {} total ({} toxic)",
                orderManager.activeOrderCount(), totalFills, toxicFills);
        log.info("  Inventory: YES={} NO={} Net={}",
                inventoryManager.getTotalYesExposure(),
                inventoryManager.getTotalNoExposure(),
                inventoryManager.getGlobalNetExposure());
        log.info("  PnL: {} realized, {} fees | Risk: {}",
                pnlService.getTotalRealizedPnl(),
                pnlService.getTotalFees(),
                riskManager.isGlobalTradingAllowed() ? "OK" : "PAUSED: " + riskManager.getPauseReason());

        for (MarketScore scored : latestRankings) {
            SimulatedMarket m = marketScanner.getMarket(scored.getMarketId());
            if (m == null) continue;
            log.info("  Market {} [{}] mid={} spread={} regime={} informed={}",
                    m.getMarketId(), m.getName(), m.getMidPrice(), m.getSpread(),
                    m.getRegime(), m.isInformedFlowActive());
        }
    }
}
