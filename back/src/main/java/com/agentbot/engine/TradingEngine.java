package com.agentbot.engine;

import com.agentbot.engine.model.*;
import com.agentbot.engine.model.SimulatedMarket.VolatilityRegime;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

@Slf4j
@Component
@RequiredArgsConstructor
public class TradingEngine {

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
    private final TradingConfig cfg;

    @Getter private TradingEngineState state = TradingEngineState.STOPPED;
    @Getter private long cycleCount = 0;
    @Getter private List<MarketScore> latestRankings = List.of();

    private int lastProcessedFillIndex = 0;
    private Set<String> previousActiveMarkets = Set.of();

    private final Map<String, Long> cooldownUntilCycle = new ConcurrentHashMap<>();
    private final AtomicInteger capViolationCount = new AtomicInteger(0);
    private final AtomicInteger cooldownsTriggered = new AtomicInteger(0);
    @Getter private BigDecimal maxCapSeen = BigDecimal.ZERO;

    public void start() {
        if (state == TradingEngineState.RUNNING) return;
        state = TradingEngineState.RUNNING;
        cycleCount = 0;
        capViolationCount.set(0);
        cooldownsTriggered.set(0);
        maxCapSeen = BigDecimal.ZERO;
        cooldownUntilCycle.clear();
        log.info("Trading engine STARTED max_cap={} block_volatile={} cooldown_cycles={}",
                cfg.getMaxCapitalSharePerMarket(), cfg.isBlockVolatileMarkets(), cfg.getCooldownCycles());
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

        latestRankings = rankingEngine.getTopMarkets(marketScanner.getAllMarkets(), cfg.getTopMarkets());

        Set<String> currentActiveMarkets = rankingEngine.getActiveMarketIds();
        Set<String> exitedMarkets = rankingEngine.getExitedMarkets(previousActiveMarkets, currentActiveMarkets);
        for (String exitedId : exitedMarkets) {
            orderManager.getActiveOrdersForMarket(exitedId)
                    .forEach(o -> orderManager.cancelOrder(o.getOrderId()));
        }
        previousActiveMarkets = Set.copyOf(currentActiveMarkets);

        processNewFills();

        riskManager.evaluateGlobalRisk(inventoryManager.getGlobalNetExposure());

        BigDecimal maxCapShare = BigDecimal.valueOf(cfg.getMaxCapitalSharePerMarket());
        BigDecimal totalEdge = latestRankings.stream()
                .map(MarketScore::getEdgeScore)
                .filter(e -> e.compareTo(BigDecimal.ZERO) > 0)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        List<String> blockedByRegime = new ArrayList<>();
        List<String> blockedByCooldown = new ArrayList<>();
        List<String> marketsOverCap = new ArrayList<>();
        BigDecimal cycleMaxCap = BigDecimal.ZERO;

        for (MarketScore scored : latestRankings) {
            SimulatedMarket market = marketScanner.getMarket(scored.getMarketId());
            if (market == null) continue;

            // --- Regime blocking ---
            VolatilityRegime regime = market.getRegime();
            if (regime == VolatilityRegime.CRISIS) {
                blockedByRegime.add(market.getMarketId());
                log.info("[REGIME_BLOCK_REAL]\nmarket={}\nregime=CRISIS\nraw_edge={}\npenalized_edge=0.0000\nthreshold={}\nreason=crisis_never_active",
                        market.getMarketId(), scored.getEdgeScore().setScale(4, RoundingMode.HALF_UP),
                        cfg.getMinEdgeAfterPenalty());
                continue;
            }

            if (regime == VolatilityRegime.VOLATILE) {
                double penalty = cfg.getRegimePenaltyVolatile();
                BigDecimal penalizedEdge = scored.getEdgeScore().multiply(BigDecimal.valueOf(penalty));
                if (cfg.isBlockVolatileMarkets()) {
                    blockedByRegime.add(market.getMarketId());
                    log.info("[REGIME_BLOCK_REAL]\nmarket={}\nregime=VOLATILE\nraw_edge={}\npenalized_edge={}\nthreshold={}\nreason=block_volatile_flag",
                            market.getMarketId(), scored.getEdgeScore().setScale(4, RoundingMode.HALF_UP),
                            penalizedEdge.setScale(4, RoundingMode.HALF_UP), cfg.getMinEdgeAfterPenalty());
                    continue;
                }
                if (penalizedEdge.doubleValue() < cfg.getMinEdgeAfterPenalty()) {
                    blockedByRegime.add(market.getMarketId());
                    log.info("[REGIME_BLOCK_REAL]\nmarket={}\nregime=VOLATILE\nraw_edge={}\npenalized_edge={}\nthreshold={}\nreason=volatile_low_penalized_edge",
                            market.getMarketId(), scored.getEdgeScore().setScale(4, RoundingMode.HALF_UP),
                            penalizedEdge.setScale(4, RoundingMode.HALF_UP), cfg.getMinEdgeAfterPenalty());
                    continue;
                }
            }

            // --- Cooldown blocking ---
            if (isOnCooldown(market.getMarketId())) {
                blockedByCooldown.add(market.getMarketId());
                log.debug("[BLOCKED]\nmarket={}\nreason=cooldown\nremaining_cycles={}",
                        market.getMarketId(), cooldownRemaining(market.getMarketId()));
                continue;
            }

            quoteSupervisor.supervise(market);

            InventoryPosition position = inventoryManager.getPosition(market.getMarketId());
            int activeForMarket = orderManager.activeOrderCountForMarket(market.getMarketId());
            int totalActive = orderManager.activeOrderCount();
            boolean allowed = riskManager.canTrade(market, position, activeForMarket, totalActive);

            // --- Hard cap ---
            BigDecimal rawCapShare = totalEdge.compareTo(BigDecimal.ZERO) > 0
                    ? scored.getEdgeScore().max(BigDecimal.ZERO).divide(totalEdge, 6, RoundingMode.HALF_UP)
                    : BigDecimal.valueOf(1.0 / Math.max(1, latestRankings.size()));

            BigDecimal capitalShare = rawCapShare;
            if (rawCapShare.compareTo(maxCapShare) > 0) {
                capitalShare = maxCapShare;
                log.info("[CAP_CLAMP_REAL]\nmarket={}\nrequested_cap={}\napplied_cap={}\nmax_cap={}\nreason=hard_limit",
                        market.getMarketId(),
                        rawCapShare.setScale(4, RoundingMode.HALF_UP),
                        capitalShare.setScale(4, RoundingMode.HALF_UP),
                        maxCapShare.setScale(4, RoundingMode.HALF_UP));
            }
            if (capitalShare.compareTo(maxCapShare) > 0) {
                marketsOverCap.add(market.getMarketId());
                capViolationCount.incrementAndGet();
            }

            cycleMaxCap = cycleMaxCap.max(capitalShare);

            strategyEngine.executeStrategy(market, position, allowed,
                    riskManager.getMaxOrdersPerSide(), scored, capitalShare);

            BigDecimal reward = rewardEngine.distributeRewards(market);
            if (reward.compareTo(BigDecimal.ZERO) > 0) {
                pnlService.recordReward(market.getMarketId(), reward);
            }
        }

        maxCapSeen = maxCapSeen.max(cycleMaxCap);

        if (cycleCount % cfg.getSnapshotInterval() == 0) {
            logRealSnapshot(blockedByRegime, blockedByCooldown, marketsOverCap, cycleMaxCap);
        }
    }

    private void processNewFills() {
        List<EngineFill> allFills = quoteSupervisor.getFills();
        int currentSize = allFills.size();
        for (int i = lastProcessedFillIndex; i < currentSize; i++) {
            EngineFill fill = allFills.get(i);
            inventoryManager.processFill(fill);
            pnlService.recordFill(fill);
            if (fill.isToxicFlow()) {
                applyCooldown(fill.getMarketId(), "toxic_fill");
            }
        }
        lastProcessedFillIndex = currentSize;
    }

    private void cancelAllOrders() {
        orderManager.getActiveOrders().forEach(o -> orderManager.cancelOrder(o.getOrderId()));
    }

    // ── Cooldown ─────────────────────────────────────────────────────────

    private void applyCooldown(String marketId, String reason) {
        long until = cycleCount + cfg.getCooldownCycles();
        cooldownUntilCycle.put(marketId, until);
        cooldownsTriggered.incrementAndGet();
        log.info("[COOLDOWN_REAL]\nmarket={}\nremaining_cycles={}\ntrigger={}",
                marketId, cfg.getCooldownCycles(), reason);
    }

    private boolean isOnCooldown(String marketId) {
        Long until = cooldownUntilCycle.get(marketId);
        if (until == null) return false;
        if (cycleCount >= until) {
            cooldownUntilCycle.remove(marketId);
            return false;
        }
        return true;
    }

    private long cooldownRemaining(String marketId) {
        Long until = cooldownUntilCycle.get(marketId);
        return until != null ? Math.max(0, until - cycleCount) : 0;
    }

    // ── Structured Logging ───────────────────────────────────────────────

    private void logRealSnapshot(List<String> blockedByRegime, List<String> blockedByCooldown,
                                  List<String> marketsOverCap, BigDecimal cycleMaxCap) {
        long totalFills = quoteSupervisor.getFills().size();
        long toxicFills = quoteSupervisor.getFills().stream().filter(EngineFill::isToxicFlow).count();

        int volatileActive = countActiveByRegime(VolatilityRegime.VOLATILE);
        int crisisActive = countActiveByRegime(VolatilityRegime.CRISIS);
        long cooledDown = cooldownUntilCycle.values().stream().filter(v -> v > cycleCount).count();

        log.info("[REAL_SNAPSHOT]\ncycle={}\nactive_markets={}\norders_active={}\nfills_total={}\nfills_toxic={}" +
                "\npnl_trading={}\npnl_reward={}\npnl_total={}\nfees={}" +
                "\nyes_exposure={}\nno_exposure={}\nnet_exposure={}" +
                "\nvolatile_active_count={}\ncrisis_active_count={}" +
                "\ncooled_down={}\ncap_violation_count={}" +
                "\nmax_cap_seen={}" +
                "\nmarkets_over_cap={}" +
                "\nblocked_by_regime={}" +
                "\nblocked_by_cooldown={}",
                cycleCount,
                rankingEngine.getActiveMarketIds().size(),
                orderManager.activeOrderCount(),
                totalFills, toxicFills,
                pnlService.getTradingPnl().setScale(4, RoundingMode.HALF_UP),
                pnlService.getTotalRewardPnl().setScale(4, RoundingMode.HALF_UP),
                pnlService.getTotalPnl().setScale(4, RoundingMode.HALF_UP),
                pnlService.getTotalFees().setScale(6, RoundingMode.HALF_UP),
                inventoryManager.getTotalYesExposure().setScale(2, RoundingMode.HALF_UP),
                inventoryManager.getTotalNoExposure().setScale(2, RoundingMode.HALF_UP),
                inventoryManager.getGlobalNetExposure().setScale(2, RoundingMode.HALF_UP),
                volatileActive, crisisActive,
                cooledDown, capViolationCount.get(),
                cycleMaxCap.setScale(4, RoundingMode.HALF_UP),
                marketsOverCap,
                blockedByRegime,
                blockedByCooldown);

        BigDecimal logTotalEdge = rankingEngine.getLatestFullRanking().stream()
                .filter(MarketScore::isSelected)
                .map(MarketScore::getEdgeScore)
                .filter(e -> e.compareTo(BigDecimal.ZERO) > 0)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal maxCap = BigDecimal.valueOf(cfg.getMaxCapitalSharePerMarket());

        for (MarketScore scored : rankingEngine.getLatestFullRanking()) {
            SimulatedMarket m = marketScanner.getMarket(scored.getMarketId());
            if (m == null) continue;
            String status = scored.isSelected() ? "ACTIVE" : "SKIP:" + scored.getRejectionReason();
            BigDecimal capShare = BigDecimal.ZERO;
            if (scored.isSelected() && logTotalEdge.compareTo(BigDecimal.ZERO) > 0) {
                capShare = scored.getEdgeScore().max(BigDecimal.ZERO)
                        .divide(logTotalEdge, 4, RoundingMode.HALF_UP).min(maxCap);
            }
            int fills = performanceTracker.getTotalFills(m.getMarketId());
            double ppf = performanceTracker.getProfitPerFill(m.getMarketId());
            log.info("  {} {} edge={} cap={}% regime={} fills={} ppf={} [{}]",
                    status, m.getMarketId(),
                    scored.getEdgeScore().setScale(4, RoundingMode.HALF_UP),
                    capShare.multiply(BigDecimal.valueOf(100)).setScale(1, RoundingMode.HALF_UP),
                    m.getRegime(), fills, BigDecimal.valueOf(ppf).setScale(2, RoundingMode.HALF_UP),
                    m.getName());
        }
    }

    private int countActiveByRegime(VolatilityRegime target) {
        return (int) rankingEngine.getActiveMarketIds().stream()
                .map(marketScanner::getMarket)
                .filter(Objects::nonNull)
                .filter(m -> m.getRegime() == target)
                .count();
    }

    // ── Getters for validation/export ────────────────────────────────────

    public int getCapViolationCount() { return capViolationCount.get(); }
    public int getCooldownsTriggered() { return cooldownsTriggered.get(); }
    public long getCooldownsActive() { return cooldownUntilCycle.values().stream().filter(v -> v > cycleCount).count(); }
}
