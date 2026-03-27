package com.agentbot.shadow;

import com.agentbot.polymarket.PolymarketConfig;
import com.agentbot.polymarket.PolymarketMarketDataClient;
import com.agentbot.polymarket.PolymarketMarketDiscoveryService;
import com.agentbot.polymarket.ShadowConfig;
import com.agentbot.polymarket.model.GammaMarket;
import com.agentbot.polymarket.model.LiveMarketState;
import com.agentbot.polymarket.model.LiveMarketState.Regime;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
@Component
@RequiredArgsConstructor
public class ShadowTradingEngine {

    private final PolymarketMarketDiscoveryService discoveryService;
    private final PolymarketConfig polymarketConfig;
    private final ShadowConfig cfg;
    private final ShadowFillModel fillModel;
    private final ShadowComparisonMetrics metrics;
    private final ObjectMapper objectMapper;

    @Getter private volatile String status = "STOPPED";
    @Getter private volatile boolean wsConnected = false;
    @Getter private volatile long cycleCount = 0;
    @Getter private volatile Instant startedAt;

    @Getter private final Map<String, LiveMarketState> liveMarkets = new ConcurrentHashMap<>();
    @Getter private final List<ShadowOrder> activeOrders = new CopyOnWriteArrayList<>();
    @Getter private final List<ShadowOrder> orderHistory = new CopyOnWriteArrayList<>();
    @Getter private final List<ShadowFill> fills = new CopyOnWriteArrayList<>();

    private final Map<String, Long> cooldownUntilCycle = new ConcurrentHashMap<>();
    private final AtomicInteger capViolationCount = new AtomicInteger(0);

    private PolymarketMarketDataClient wsClient;
    private ScheduledExecutorService scheduler;
    private ScheduledFuture<?> cycleFuture;
    private ScheduledFuture<?> refreshFuture;

    private static final BigDecimal BD2 = BigDecimal.valueOf(2);
    private static final DateTimeFormatter TS_FMT = DateTimeFormatter
            .ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());

    // ── Lifecycle ────────────────────────────────────────────────────────

    public void start() {
        if ("RUNNING".equals(status)) return;
        status = "STARTING";
        startedAt = Instant.now();
        cycleCount = 0;
        capViolationCount.set(0);
        metrics.reset();
        activeOrders.clear();
        orderHistory.clear();
        fills.clear();
        liveMarkets.clear();
        cooldownUntilCycle.clear();

        scheduler = Executors.newScheduledThreadPool(2);
        scheduler.execute(this::discoverAndConnect);
        refreshFuture = scheduler.scheduleAtFixedRate(this::refreshMarkets,
                cfg.getRefreshIntervalMs(), cfg.getRefreshIntervalMs(), TimeUnit.MILLISECONDS);
        cycleFuture = scheduler.scheduleAtFixedRate(this::runShadowCycle,
                2000, cfg.getCycleIntervalMs(), TimeUnit.MILLISECONDS);
        status = "RUNNING";
        log.info("Shadow engine STARTED budget={} max_cap={} edge_clamp=[{},{}]",
                getTotalBudget(), cfg.getMaxCapitalSharePerMarket(),
                cfg.getEdgeClampMin(), cfg.getEdgeClampMax());
    }

    public void stop() {
        status = "STOPPING";
        if (wsClient != null && wsClient.isOpen()) wsClient.close();
        if (cycleFuture != null) cycleFuture.cancel(false);
        if (refreshFuture != null) refreshFuture.cancel(false);
        if (scheduler != null) scheduler.shutdown();
        for (ShadowOrder o : activeOrders) {
            o.setStatus("CANCELLED"); o.setCancelledAt(Instant.now()); orderHistory.add(o);
        }
        activeOrders.clear();
        logRunSummary();
        status = "STOPPED";
        wsConnected = false;
    }

    private BigDecimal getTotalBudget() {
        return BigDecimal.valueOf(cfg.getMaxOrderSize()).multiply(BigDecimal.valueOf(cfg.getMaxMarkets()));
    }

    // ── Discovery & WebSocket ────────────────────────────────────────────

    private void discoverAndConnect() {
        try {
            List<GammaMarket> mkts = discoveryService.fetchActiveMarkets(50);
            if (mkts.isEmpty()) { log.warn("No active markets found"); return; }
            Map<String, LiveMarketState> states = discoveryService.buildLiveMarketStates(mkts, cfg.getMaxMarkets());
            liveMarkets.putAll(states);
            var books = discoveryService.fetchOrderBooks(liveMarkets.keySet());
            for (var e : books.entrySet()) {
                LiveMarketState s = liveMarkets.get(e.getKey());
                if (s != null) s.updateBbo(e.getValue()[0], e.getValue()[1]);
            }
            connectWebSocket();
            log.info("Shadow engine initialized with {} tokens", liveMarkets.size());
        } catch (Exception e) { log.error("Discovery failed: {}", e.getMessage()); }
    }

    private void connectWebSocket() {
        try {
            if (wsClient != null && wsClient.isOpen()) wsClient.close();
            URI wsUri = new URI(polymarketConfig.getClobWsUrl());
            wsClient = new PolymarketMarketDataClient(wsUri, objectMapper, liveMarkets, new ArrayList<>(liveMarkets.keySet()));
            wsClient.connectBlocking(10, TimeUnit.SECONDS);
            wsConnected = wsClient.isOpen();
            log.info("WebSocket: {}", wsConnected ? "CONNECTED" : "FAILED");
        } catch (Exception e) { log.error("WS failed: {}", e.getMessage()); wsConnected = false; }
    }

    private void refreshMarkets() {
        try {
            var books = discoveryService.fetchOrderBooks(liveMarkets.keySet());
            for (var e : books.entrySet()) {
                LiveMarketState s = liveMarkets.get(e.getKey());
                if (s != null) s.updateBbo(e.getValue()[0], e.getValue()[1]);
            }
            if (wsClient == null || !wsClient.isOpen()) { connectWebSocket(); }
            wsConnected = wsClient != null && wsClient.isOpen();
        } catch (Exception e) { log.debug("Refresh error: {}", e.getMessage()); }
    }

    // ── Main Cycle ───────────────────────────────────────────────────────

    private void runShadowCycle() {
        if (!"RUNNING".equals(status)) return;
        try {
            cancelStaleOrders();
            evaluateFills();
            placeHypotheticalQuotes();
            auditCapConcentration();
            cycleCount++;
            if (cycleCount % cfg.getCycleSummaryInterval() == 0) logCycleSummary();
        } catch (Exception e) { log.error("Shadow cycle error: {}", e.getMessage(), e); }
    }

    private void cancelStaleOrders() {
        Duration staleAge = Duration.ofSeconds(cfg.getStaleOrderTimeoutSec());
        List<ShadowOrder> stale = activeOrders.stream()
                .filter(o -> Duration.between(o.getCreatedAt(), Instant.now()).compareTo(staleAge) > 0)
                .toList();
        for (ShadowOrder o : stale) {
            long ageSec = Duration.between(o.getCreatedAt(), Instant.now()).getSeconds();
            o.setStatus("CANCELLED"); o.setCancelledAt(Instant.now());
            orderHistory.add(o); activeOrders.remove(o);
            log.info("[CANCEL]\norder_id={}\nmarket={}\nside={}\nprice={}\nsize={}\nage_sec={}\nreason=stale_timeout",
                    o.getOrderId(), shortId(o.getTokenId()), o.getSide(),
                    o.getPrice().setScale(4, RoundingMode.HALF_UP),
                    o.getSize().setScale(0, RoundingMode.HALF_UP), ageSec);
        }
    }

    private void evaluateFills() {
        int evaluated = 0, crossable = 0, filled = 0;
        List<ShadowOrder> toRemove = new ArrayList<>();
        for (ShadowOrder order : activeOrders) {
            LiveMarketState s = liveMarkets.get(order.getTokenId());
            if (s == null) continue;
            evaluated++;
            Optional<ShadowFill> fill = fillModel.evaluateFill(order, s);
            if (fill.isPresent()) {
                ShadowFill f = fill.get();
                fills.add(f); metrics.recordFill(f);
                order.setStatus("FILLED"); orderHistory.add(order); toRemove.add(order);
                filled++;
                if (f.isWouldHaveBeenToxic()) applyCooldown(f.getTokenId(), "toxic_fill");
            }
        }
        activeOrders.removeAll(toRemove);
        log.debug("[SHADOW_DIAG]\nopen_orders={}\nevaluated_orders={}\nfilled_orders={}\nreason_no_fills={}",
                activeOrders.size() + filled, evaluated, filled,
                filled == 0 ? "no_bbo_cross" : "ok");
    }

    // ── Edge Calculation ─────────────────────────────────────────────────
    // Edge = half-spread as fraction of mid price, clamped to [edgeClampMin, edgeClampMax].
    // rawEdge = spread / (2 * mid)  — the half-spread yield a market maker captures per fill.
    // penalizedEdge = rawEdge * regimePenalty
    // finalEdge = clamp(penalizedEdge, min, max)

    private BigDecimal computeRawEdge(LiveMarketState s) {
        BigDecimal spread = s.getSpread();
        BigDecimal mid = s.getMidPrice();
        if (spread.compareTo(BigDecimal.ZERO) <= 0 || mid.compareTo(BigDecimal.ZERO) <= 0)
            return BigDecimal.ZERO;
        return spread.divide(mid.multiply(BD2), 6, RoundingMode.HALF_UP);
    }

    private BigDecimal clampEdge(BigDecimal edge, String tokenId, BigDecimal rawEdge) {
        BigDecimal min = BigDecimal.valueOf(cfg.getEdgeClampMin());
        BigDecimal max = BigDecimal.valueOf(cfg.getEdgeClampMax());
        if (edge.compareTo(min) < 0 || edge.compareTo(max) > 0) {
            BigDecimal clamped = edge.max(min).min(max);
            log.info("[EDGE_CLAMP]\nmarket={}\nraw_edge={}\nclamped_edge={}\nreason=out_of_range",
                    shortId(tokenId), rawEdge.setScale(6, RoundingMode.HALF_UP),
                    clamped.setScale(6, RoundingMode.HALF_UP));
            return clamped;
        }
        return edge;
    }

    private double getRegimePenalty(Regime r) {
        return switch (r) {
            case CALM -> cfg.getRegimePenaltyCalm();
            case NORMAL -> cfg.getRegimePenaltyNormal();
            case VOLATILE -> cfg.getRegimePenaltyVolatile();
            case CRISIS -> cfg.getRegimePenaltyCrisis();
        };
    }

    private double getSizeScaleByRegime(Regime r) {
        return switch (r) {
            case CALM -> cfg.getSizeScaleCalm();
            case NORMAL -> cfg.getSizeScaleNormal();
            case VOLATILE -> cfg.getSizeScaleVolatile();
            case CRISIS -> cfg.getSizeScaleCrisis();
        };
    }

    // ── Inventory ────────────────────────────────────────────────────────

    private BigDecimal getYesExposure() { return metrics.getYesExposureCurrent(); }
    private BigDecimal getNoExposure() { return metrics.getNoExposureCurrent(); }
    private BigDecimal getNetExposure() { return getYesExposure().subtract(getNoExposure()); }

    private double computeInventoryPenalty() {
        double net = getNetExposure().abs().doubleValue();
        double maxNet = cfg.getMaxNetExposure();
        if (maxNet <= 0) return 1.0;
        double ratio = net / maxNet;
        return Math.max(0.0, 1.0 - cfg.getInventoryPenaltyK() * ratio);
    }

    private boolean isInventoryBlocked(String side) {
        if ("BUY".equals(side)) {
            return getYesExposure().doubleValue() >= cfg.getMaxYesExposure()
                    || getNetExposure().doubleValue() >= cfg.getMaxNetExposure();
        } else {
            return getNoExposure().doubleValue() >= cfg.getMaxNoExposure()
                    || getNetExposure().negate().doubleValue() >= cfg.getMaxNetExposure();
        }
    }

    private String getRebalanceBias() {
        if (!cfg.isRebalanceBiasEnabled()) return "NONE";
        double net = getNetExposure().doubleValue();
        if (net > cfg.getMaxNetExposure() * 0.5) return "SELL";
        if (net < -cfg.getMaxNetExposure() * 0.5) return "BUY";
        return "NONE";
    }

    // ── Cooldown ─────────────────────────────────────────────────────────

    private void applyCooldown(String tokenId, String reason) {
        long until = cycleCount + cfg.getCooldownCycles();
        cooldownUntilCycle.put(tokenId, until);
        log.info("[COOLDOWN_START]\nmarket={}\ncycles={}\nreason={}",
                shortId(tokenId), cfg.getCooldownCycles(), reason);
    }

    private boolean isOnCooldown(String tokenId) {
        Long until = cooldownUntilCycle.get(tokenId);
        if (until == null) return false;
        if (cycleCount >= until) {
            cooldownUntilCycle.remove(tokenId);
            log.info("[COOLDOWN_END]\nmarket={}", shortId(tokenId));
            return false;
        }
        return true;
    }

    private long cooldownRemaining(String tokenId) {
        Long until = cooldownUntilCycle.get(tokenId);
        return until != null ? Math.max(0, until - cycleCount) : 0;
    }

    // ── Quoting ──────────────────────────────────────────────────────────

    private void placeHypotheticalQuotes() {
        BigDecimal maxCapShare = BigDecimal.valueOf(cfg.getMaxCapitalSharePerMarket());
        BigDecimal totalBudget = getTotalBudget();
        BigDecimal maxSizePerMarket = totalBudget.multiply(maxCapShare);
        double invPenalty = computeInventoryPenalty();
        String rebalBias = getRebalanceBias();

        Map<String, BigDecimal[]> edgeMap = new LinkedHashMap<>();
        BigDecimal totalEdge = BigDecimal.ZERO;

        for (var entry : liveMarkets.entrySet()) {
            String tokenId = entry.getKey();
            LiveMarketState s = entry.getValue();
            BigDecimal mid = s.getMidPrice();
            BigDecimal spread = s.getSpread();
            Regime regime = s.getRegime();

            BigDecimal rawEdge = computeRawEdge(s);
            BigDecimal penalizedEdge = rawEdge.multiply(BigDecimal.valueOf(getRegimePenalty(regime)));
            BigDecimal finalEdge = clampEdge(penalizedEdge, tokenId, rawEdge);

            String decisionStatus;
            String decisionReason;

            if (mid.compareTo(BigDecimal.ZERO) <= 0 || spread.compareTo(BigDecimal.ZERO) <= 0) {
                decisionStatus = "SKIP"; decisionReason = "invalid_bbo";
            } else if (isOnCooldown(tokenId)) {
                decisionStatus = "BLOCKED"; decisionReason = "cooldown";
                log.debug("[BLOCKED]\nmarket={}\nreason=cooldown\nremaining_cycles={}",
                        shortId(tokenId), cooldownRemaining(tokenId));
            } else if (regime == Regime.CRISIS) {
                decisionStatus = "BLOCKED"; decisionReason = "crisis_regime";
                log.info("[REGIME_BLOCK]\nmarket={}\nregime=CRISIS\nraw_edge={}\npenalized_edge={}\nthreshold={}\nblocked=true",
                        shortId(tokenId), fmt(rawEdge), fmt(penalizedEdge), cfg.getMinEdgeAfterPenalty());
            } else if (regime == Regime.VOLATILE && cfg.isBlockVolatileMarkets()) {
                decisionStatus = "BLOCKED"; decisionReason = "volatile_blocked";
                log.info("[REGIME_BLOCK]\nmarket={}\nregime=VOLATILE\nraw_edge={}\npenalized_edge={}\nthreshold={}\nblocked=true",
                        shortId(tokenId), fmt(rawEdge), fmt(penalizedEdge), cfg.getMinEdgeAfterPenalty());
            } else if (regime == Regime.VOLATILE) {
                if (finalEdge.doubleValue() < cfg.getMinEdgeAfterPenalty()) {
                    decisionStatus = "BLOCKED"; decisionReason = "volatile_low_edge";
                    log.info("[REGIME_BLOCK]\nmarket={}\nregime=VOLATILE\nraw_edge={}\npenalized_edge={}\nthreshold={}\nblocked=true",
                            shortId(tokenId), fmt(rawEdge), fmt(finalEdge), cfg.getMinEdgeAfterPenalty());
                } else {
                    decisionStatus = "ACTIVE"; decisionReason = "volatile_edge_ok";
                    edgeMap.put(tokenId, new BigDecimal[]{rawEdge, penalizedEdge, finalEdge});
                    totalEdge = totalEdge.add(finalEdge);
                }
            } else if (finalEdge.doubleValue() < cfg.getMinEdgeAfterPenalty()) {
                decisionStatus = "SKIP"; decisionReason = "edge_below_min";
            } else {
                decisionStatus = "ACTIVE"; decisionReason = "ok";
                edgeMap.put(tokenId, new BigDecimal[]{rawEdge, penalizedEdge, finalEdge});
                totalEdge = totalEdge.add(finalEdge);
            }

            log.debug("[MARKET_DECISION]\nmarket={}\nstatus={}\nreason={}\nraw_edge={}\npenalized_edge={}\nfinal_edge={}\nregime={}\nspread={}\nmid={}\ncooldown_remaining={}",
                    shortId(tokenId), decisionStatus, decisionReason,
                    fmt(rawEdge), fmt(penalizedEdge), fmt(finalEdge),
                    regime, fmt(spread), fmt(mid), cooldownRemaining(tokenId));
        }

        if (edgeMap.isEmpty()) return;

        Map<String, BigDecimal> capAllocs = computeCapitalAllocations(edgeMap, totalEdge, maxCapShare);

        for (var entry : capAllocs.entrySet()) {
            String tokenId = entry.getKey();
            BigDecimal capShare = entry.getValue();
            LiveMarketState s = liveMarkets.get(tokenId);
            if (s == null) continue;
            BigDecimal mid = s.getMidPrice();
            BigDecimal spread = s.getSpread();
            Regime regime = s.getRegime();
            BigDecimal[] edges = edgeMap.get(tokenId);
            BigDecimal finalEdge = edges[2];

            long activeForToken = activeOrders.stream().filter(o -> o.getTokenId().equals(tokenId)).count();
            if (activeForToken >= 2) continue;

            BigDecimal existingSize = activeOrders.stream()
                    .filter(o -> o.getTokenId().equals(tokenId))
                    .map(ShadowOrder::getSize).reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal remainingSize = maxSizePerMarket.subtract(existingSize);
            if (remainingSize.compareTo(BigDecimal.valueOf(cfg.getMinOrderSize())) < 0) continue;

            boolean hasBuy = activeOrders.stream().anyMatch(o -> o.getTokenId().equals(tokenId) && "BUY".equals(o.getSide()));
            boolean hasSell = activeOrders.stream().anyMatch(o -> o.getTokenId().equals(tokenId) && "SELL".equals(o.getSide()));
            int ordersToPlace = (!hasBuy ? 1 : 0) + (!hasSell ? 1 : 0);

            double regimeScale = getSizeScaleByRegime(regime);
            double confidenceScale = Math.min(1.0, finalEdge.doubleValue() / Math.max(0.01, cfg.getEdgeClampMax()));

            BigDecimal rawSize = remainingSize;
            if (ordersToPlace > 1) rawSize = remainingSize.divide(BigDecimal.valueOf(ordersToPlace), 0, RoundingMode.FLOOR);
            BigDecimal finalSize = rawSize
                    .multiply(BigDecimal.valueOf(regimeScale))
                    .multiply(BigDecimal.valueOf(invPenalty))
                    .multiply(BigDecimal.valueOf(confidenceScale))
                    .setScale(0, RoundingMode.FLOOR)
                    .max(BigDecimal.valueOf(cfg.getMinOrderSize()))
                    .min(BigDecimal.valueOf(cfg.getMaxOrderSize()));

            log.debug("[SIZE_DECISION]\nmarket={}\nraw_size={}\nfinal_size={}\ncap_remaining={}\nregime_scale={}\ninventory_scale={}\nconfidence_scale={}",
                    shortId(tokenId), rawSize.setScale(0, RoundingMode.HALF_UP),
                    finalSize, remainingSize.setScale(0, RoundingMode.HALF_UP),
                    regimeScale, String.format("%.4f", invPenalty), String.format("%.4f", confidenceScale));

            BigDecimal aggr = BigDecimal.valueOf(cfg.getQuoteAggressiveness());
            BigDecimal halfSpread = spread.divide(BD2, 4, RoundingMode.HALF_UP);
            BigDecimal quoteOffset = halfSpread.multiply(BigDecimal.ONE.subtract(aggr));
            BigDecimal bidPrice = mid.subtract(quoteOffset).setScale(2, RoundingMode.HALF_UP);
            BigDecimal askPrice = mid.add(quoteOffset).setScale(2, RoundingMode.HALF_UP);
            BigDecimal minP = new BigDecimal("0.05");
            BigDecimal maxP = new BigDecimal("0.95");

            if (!hasBuy && bidPrice.compareTo(minP) > 0) {
                if (isInventoryBlocked("BUY")) {
                    log.info("[BLOCKED]\nmarket={}\nside=BUY\nreason=inventory_limit\nyes_exposure={}\nno_exposure={}\nnet_exposure={}",
                            shortId(tokenId), fmt(getYesExposure()), fmt(getNoExposure()), fmt(getNetExposure()));
                } else {
                    ShadowOrder buy = fillModel.createHypotheticalOrder(
                            tokenId, s.getQuestion(), s.getOutcome(),
                            "BUY", bidPrice, finalSize, s, finalEdge, capShare);
                    activeOrders.add(buy);
                    metrics.recordQuote();
                }
            }

            if (!hasSell && askPrice.compareTo(maxP) < 0) {
                if (isInventoryBlocked("SELL")) {
                    log.info("[BLOCKED]\nmarket={}\nside=SELL\nreason=inventory_limit\nyes_exposure={}\nno_exposure={}\nnet_exposure={}",
                            shortId(tokenId), fmt(getYesExposure()), fmt(getNoExposure()), fmt(getNetExposure()));
                } else {
                    ShadowOrder sell = fillModel.createHypotheticalOrder(
                            tokenId, s.getQuestion(), s.getOutcome(),
                            "SELL", askPrice, finalSize, s, finalEdge, capShare);
                    activeOrders.add(sell);
                    metrics.recordQuote();
                }
            }
        }
    }

    private Map<String, BigDecimal> computeCapitalAllocations(
            Map<String, BigDecimal[]> edgeMap, BigDecimal totalEdge, BigDecimal maxCapShare) {
        if (edgeMap.isEmpty() || totalEdge.compareTo(BigDecimal.ZERO) <= 0) return Collections.emptyMap();
        Map<String, BigDecimal> allocs = new LinkedHashMap<>();
        BigDecimal excess = BigDecimal.ZERO;
        Set<String> capped = new HashSet<>();

        for (var e : edgeMap.entrySet()) {
            BigDecimal raw = e.getValue()[2].divide(totalEdge, 6, RoundingMode.HALF_UP);
            if (raw.compareTo(maxCapShare) > 0) {
                allocs.put(e.getKey(), maxCapShare);
                excess = excess.add(raw.subtract(maxCapShare));
                capped.add(e.getKey());
                log.debug("[CAP_CLAMP]\nmarket={}\nrequested_cap={}\napplied_cap={}\nmax_cap={}\nreason=hard_limit",
                        shortId(e.getKey()), fmt(raw), fmt(maxCapShare), fmt(maxCapShare));
            } else {
                allocs.put(e.getKey(), raw);
            }
        }

        if (excess.compareTo(BigDecimal.ZERO) > 0 && allocs.size() > capped.size()) {
            BigDecimal uncSum = allocs.entrySet().stream()
                    .filter(e -> !capped.contains(e.getKey()))
                    .map(Map.Entry::getValue).reduce(BigDecimal.ZERO, BigDecimal::add);
            if (uncSum.compareTo(BigDecimal.ZERO) > 0) {
                for (var e : allocs.entrySet()) {
                    if (capped.contains(e.getKey())) continue;
                    BigDecimal bonus = excess.multiply(e.getValue().divide(uncSum, 6, RoundingMode.HALF_UP));
                    BigDecimal newA = e.getValue().add(bonus).min(maxCapShare);
                    allocs.put(e.getKey(), newA);
                }
            }
        }
        return allocs;
    }

    // ── Post-Hoc Audit ───────────────────────────────────────────────────

    private void auditCapConcentration() {
        if (activeOrders.isEmpty()) return;
        BigDecimal totalBudget = getTotalBudget();
        BigDecimal maxSize = totalBudget.multiply(BigDecimal.valueOf(cfg.getMaxCapitalSharePerMarket()));

        Map<String, BigDecimal> sizePerToken = new HashMap<>();
        for (ShadowOrder o : activeOrders) sizePerToken.merge(o.getTokenId(), o.getSize(), BigDecimal::add);

        for (var e : sizePerToken.entrySet()) {
            if (e.getValue().compareTo(maxSize) > 0) {
                capViolationCount.incrementAndGet();
                log.warn("[CAP_VIOLATION]\nmarket={}\nmarket_size={}\nmax_size={}\nbudget_share={}",
                        shortId(e.getKey()), e.getValue().setScale(0, RoundingMode.HALF_UP),
                        maxSize.setScale(0, RoundingMode.HALF_UP),
                        e.getValue().divide(totalBudget, 4, RoundingMode.HALF_UP));
            }
        }
    }

    // ── Regime Counters ──────────────────────────────────────────────────

    private int countByRegime(Regime target) {
        return (int) liveMarkets.values().stream().filter(s -> s.getRegime() == target).count();
    }

    private int countActiveByRegime(Regime target) {
        Set<String> active = new HashSet<>();
        for (ShadowOrder o : activeOrders) active.add(o.getTokenId());
        return (int) active.stream().map(liveMarkets::get).filter(Objects::nonNull)
                .filter(s -> s.getRegime() == target).count();
    }

    // ── Structured Logging ───────────────────────────────────────────────

    private void logCycleSummary() {
        long cooled = cooldownUntilCycle.values().stream().filter(v -> v > cycleCount).count();

        log.info("[CYCLE_SUMMARY]\ncycle={}\norders_active={}\nfills_total={}\nfills_toxic={}" +
                "\npnl_trading={}\npnl_reward=0.0000\npnl_total={}\nfees={}" +
                "\nyes_exposure={}\nno_exposure={}\nnet_exposure={}" +
                "\nmax_yes_exposure={}\nmax_no_exposure={}\nmax_net_exposure={}" +
                "\nactive_markets={}\ncooldown_markets={}\nvolatile_markets={}\ncrisis_markets={}" +
                "\ncap_violation_count={}" +
                "\ninventory_penalty={}\nrebalance_bias={}",
                cycleCount, activeOrders.size(),
                metrics.getTotalFills(), metrics.getToxicFills(),
                fmt(metrics.getTotalPnl()), fmt(metrics.getTotalPnl()),
                metrics.getTotalFees().setScale(6, RoundingMode.HALF_UP),
                fmt(getYesExposure()), fmt(getNoExposure()), fmt(getNetExposure()),
                fmt(metrics.getMaxYes()), fmt(metrics.getMaxNo()), fmt(metrics.getMaxNet()),
                liveMarkets.size(), cooled,
                countByRegime(Regime.VOLATILE), countByRegime(Regime.CRISIS),
                capViolationCount.get(),
                String.format("%.4f", computeInventoryPenalty()), getRebalanceBias());
    }

    private void logRunSummary() {
        Instant end = Instant.now();
        long dur = startedAt != null ? Duration.between(startedAt, end).getSeconds() : 0;
        StringBuilder sb = new StringBuilder("\n---\n\n");
        sb.append("RUN: shadow-").append(startedAt != null
                ? DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneId.systemDefault()).format(startedAt)
                : "unknown").append('\n');
        sb.append("START: ").append(startedAt != null ? TS_FMT.format(startedAt) : "N/A").append('\n');
        sb.append("END: ").append(TS_FMT.format(end)).append('\n');
        sb.append("DURATION_SEC: ").append(dur).append('\n');
        sb.append('\n');
        sb.append("FINAL:\n");
        sb.append("fills=").append(metrics.getTotalFills()).append('\n');
        sb.append("toxic_fills=").append(metrics.getToxicFills()).append('\n');
        sb.append("trading_pnl=").append(fmt(metrics.getTotalPnl())).append('\n');
        sb.append("reward_pnl=0.0000\n");
        sb.append("total_pnl=").append(fmt(metrics.getTotalPnl())).append('\n');
        sb.append("fees=").append(metrics.getTotalFees().setScale(6, RoundingMode.HALF_UP)).append('\n');
        sb.append("yes_exposure=").append(fmt(getYesExposure())).append('\n');
        sb.append("no_exposure=").append(fmt(getNoExposure())).append('\n');
        sb.append("net_exposure=").append(fmt(getNetExposure())).append('\n');
        sb.append("max_yes=").append(fmt(metrics.getMaxYes())).append('\n');
        sb.append("max_no=").append(fmt(metrics.getMaxNo())).append('\n');
        sb.append("max_net=").append(fmt(metrics.getMaxNet())).append('\n');
        sb.append("max_drawdown=").append(fmt(metrics.getMaxDrawdown())).append('\n');
        sb.append("cap_violations=").append(capViolationCount.get()).append('\n');
        sb.append("cycles=").append(cycleCount).append('\n');
        sb.append("live_markets=").append(liveMarkets.size()).append('\n');
        sb.append("ws_connected=").append(wsConnected).append('\n');
        sb.append("max_cap_share=").append(cfg.getMaxCapitalSharePerMarket()).append('\n');
        sb.append("total_budget=").append(getTotalBudget()).append('\n');

        sb.append('\n');
        sb.append("TOP_PNL_MARKETS:\n");
        var topPnl = metrics.getTopPnlMarkets(3);
        for (int i = 0; i < 3; i++) {
            if (i < topPnl.size()) {
                var e = topPnl.get(i);
                sb.append(i + 1).append(". ").append(shortId(e.getKey()))
                        .append(" pnl=").append(fmt(e.getValue().getPnl())).append('\n');
            } else sb.append(i + 1).append(". none pnl=0.0000\n");
        }
        sb.append('\n');
        sb.append("TOP_TOXIC_MARKETS:\n");
        var topToxic = metrics.getTopToxicMarkets(3);
        for (int i = 0; i < 3; i++) {
            if (i < topToxic.size()) {
                var e = topToxic.get(i);
                sb.append(i + 1).append(". ").append(shortId(e.getKey()))
                        .append(" toxic=").append(e.getValue().getToxicFills()).append('\n');
            } else sb.append(i + 1).append(". none toxic=0\n");
        }
        sb.append("\n---");
        log.info("{}", sb);
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    private static String shortId(String id) {
        return id != null && id.length() > 16 ? id.substring(0, 16) : (id != null ? id : "null");
    }

    private static String fmt(BigDecimal v) {
        return v != null ? v.setScale(4, RoundingMode.HALF_UP).toPlainString() : "0.0000";
    }
}
