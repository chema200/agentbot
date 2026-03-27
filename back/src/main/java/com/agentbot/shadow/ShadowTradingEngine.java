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
    private final ShadowMarketGuardService guardService;
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
    @Getter private final AtomicInteger capViolationCount = new AtomicInteger(0);
    @Getter private final AtomicInteger cooldownsTriggered = new AtomicInteger(0);
    @Getter private final AtomicInteger orderRejectCount = new AtomicInteger(0);

    private PolymarketMarketDataClient wsClient;
    private ScheduledExecutorService scheduler;
    private ScheduledFuture<?> cycleFuture;
    private ScheduledFuture<?> refreshFuture;

    private static final BigDecimal BD2 = BigDecimal.valueOf(2);
    private static final BigDecimal MIN_PRICE = new BigDecimal("0.03");
    private static final BigDecimal MAX_PRICE = new BigDecimal("0.97");
    private static final BigDecimal MIN_QUOTABLE_MID = new BigDecimal("0.04");
    private static final BigDecimal MAX_QUOTABLE_MID = new BigDecimal("0.96");
    private static final DateTimeFormatter TS_FMT = DateTimeFormatter
            .ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());

    // ── Lifecycle ────────────────────────────────────────────────────────

    public void start() {
        if ("RUNNING".equals(status)) return;
        status = "STARTING";
        startedAt = Instant.now();
        cycleCount = 0;
        capViolationCount.set(0);
        cooldownsTriggered.set(0);
        orderRejectCount.set(0);
        metrics.reset();
        guardService.reset();
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
        log.info("Shadow engine STARTED budget={} max_cap={} edge_clamp=[{},{}] min_price={} max_price={}",
                getTotalBudget(), cfg.getMaxCapitalSharePerMarket(),
                cfg.getEdgeClampMin(), cfg.getEdgeClampMax(), MIN_PRICE, MAX_PRICE);
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
            guardService.setCycleCount(cycleCount);
            guardService.tick(cycleCount);
            cancelStaleOrders();
            evaluateFills();
            placeHypotheticalQuotes();
            auditCapConcentration();
            cycleCount++;
            if (cycleCount % cfg.getCycleSummaryInterval() == 0) logCycleSummary();
            if (cycleCount % cfg.getGuardQualitySnapshotInterval() == 0) guardService.logQualitySnapshots();
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
            guardService.recordCancel(o.getTokenId(), "stale_timeout");
            log.info("[CANCEL]\norder_id={}\nmarket={}\nside={}\nprice={}\nsize={}\nage_sec={}\nreason=stale_timeout",
                    o.getOrderId(), shortId(o.getTokenId()), o.getSide(),
                    o.getPrice().setScale(4, RoundingMode.HALF_UP),
                    o.getSize().setScale(0, RoundingMode.HALF_UP), ageSec);
        }
    }

    private void evaluateFills() {
        List<ShadowOrder> toRemove = new ArrayList<>();
        for (ShadowOrder order : activeOrders) {
            LiveMarketState s = liveMarkets.get(order.getTokenId());
            if (s == null) continue;
            Optional<ShadowFill> fill = fillModel.evaluateFill(order, s);
            if (fill.isPresent()) {
                ShadowFill f = fill.get();
                fills.add(f);
                metrics.recordFill(f);
                guardService.recordFill(f);
                order.setStatus("FILLED"); orderHistory.add(order); toRemove.add(order);
                if (f.isWouldHaveBeenToxic()) {
                    applyCooldown(f.getTokenId(), "toxic_fill");
                }
            }
        }
        activeOrders.removeAll(toRemove);
    }

    // ── Edge Calculation ─────────────────────────────────────────────────

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
                    shortId(tokenId), fmt(rawEdge), fmt(clamped));
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
        return Math.max(cfg.getInventoryMinScale(), 1.0 - cfg.getInventoryPenaltyK() * ratio);
    }

    /**
     * Instead of hard blocking, returns a scaling factor [inventoryMinScale..1.0].
     * At the hard limit (100%), returns inventoryMinScale (e.g. 0.15).
     * Beyond 120% of limit, returns 0 (true hard block).
     * This prevents the "everything blocked" pattern while still limiting exposure.
     */
    private double computeInventorySideScale(String side) {
        double maxYes = cfg.getMaxYesExposure();
        double maxNo = cfg.getMaxNoExposure();
        double maxNet = cfg.getMaxNetExposure();
        double minScale = cfg.getInventoryMinScale();

        if ("BUY".equals(side)) {
            double yesRatio = maxYes > 0 ? getYesExposure().doubleValue() / maxYes : 0;
            double netRatio = maxNet > 0 ? Math.max(0, getNetExposure().doubleValue()) / maxNet : 0;
            double worstRatio = Math.max(yesRatio, netRatio);
            if (worstRatio >= 1.2) return 0.0;
            if (worstRatio >= 0.5) return Math.max(minScale, 1.0 - (worstRatio - 0.5) / 0.5 * (1.0 - minScale));
            return 1.0;
        } else {
            double noRatio = maxNo > 0 ? getNoExposure().doubleValue() / maxNo : 0;
            double netRatio = maxNet > 0 ? Math.max(0, getNetExposure().negate().doubleValue()) / maxNet : 0;
            double worstRatio = Math.max(noRatio, netRatio);
            if (worstRatio >= 1.2) return 0.0;
            if (worstRatio >= 0.5) return Math.max(minScale, 1.0 - (worstRatio - 0.5) / 0.5 * (1.0 - minScale));
            return 1.0;
        }
    }

    // ── Cooldown ─────────────────────────────────────────────────────────

    private void applyCooldown(String tokenId, String reason) {
        long until = cycleCount + cfg.getCooldownCycles();
        cooldownUntilCycle.put(tokenId, until);
        cooldownsTriggered.incrementAndGet();
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

            String decisionStatus = "ACTIVE";
            String decisionReason = "ok";

            if (mid.compareTo(BigDecimal.ZERO) <= 0 || spread.compareTo(BigDecimal.ZERO) <= 0) {
                decisionStatus = "SKIP"; decisionReason = "invalid_bbo";
            } else if (mid.compareTo(MIN_QUOTABLE_MID) < 0 || mid.compareTo(MAX_QUOTABLE_MID) > 0) {
                decisionStatus = "SKIP"; decisionReason = "extreme_price";
            } else if (isOnCooldown(tokenId)) {
                decisionStatus = "BLOCKED"; decisionReason = "cooldown";
            } else if (!guardService.shouldQuote(tokenId)) {
                decisionStatus = "BLOCKED"; decisionReason = "market_guard_" + guardService.getStatus(tokenId);
            } else if (regime == Regime.CRISIS) {
                decisionStatus = "BLOCKED"; decisionReason = "crisis_regime";
                log.info("[REGIME_BLOCK]\nmarket={}\nregime=CRISIS\nraw_edge={}\npenalized_edge={}\nblocked=true",
                        shortId(tokenId), fmt(rawEdge), fmt(penalizedEdge));
            } else if (regime == Regime.VOLATILE && cfg.isBlockVolatileMarkets()) {
                decisionStatus = "BLOCKED"; decisionReason = "volatile_blocked";
            } else if (regime == Regime.VOLATILE && finalEdge.doubleValue() < cfg.getMinEdgeAfterPenalty()) {
                decisionStatus = "BLOCKED"; decisionReason = "volatile_low_edge";
            } else if (finalEdge.doubleValue() < cfg.getMinEdgeAfterPenalty()) {
                decisionStatus = "SKIP"; decisionReason = "edge_below_min";
            } else {
                double gp = guardService.getGuardPenalty(tokenId);
                if (gp > 0.0) {
                    BigDecimal guardedEdge = finalEdge.multiply(BigDecimal.valueOf(1.0 - gp));
                    log.debug("[MARKET_GUARD_PENALTY]\nmarket={}\nraw_edge={}\nguard_penalty={}\nfinal_edge={}\nreason=guard_applied",
                            shortId(tokenId), fmt(finalEdge), String.format("%.4f", gp), fmt(guardedEdge));
                    finalEdge = guardedEdge;
                    if (finalEdge.doubleValue() < cfg.getMinEdgeAfterPenalty()) {
                        decisionStatus = "SKIP"; decisionReason = "edge_below_min_after_guard";
                    }
                }
                if (!"SKIP".equals(decisionStatus)) {
                    decisionStatus = "ACTIVE"; decisionReason = "ok";
                    guardService.recordQuoteAttempt(tokenId);
                    edgeMap.put(tokenId, new BigDecimal[]{rawEdge, penalizedEdge, finalEdge});
                    totalEdge = totalEdge.add(finalEdge);
                }
            }

            log.debug("[MARKET_DECISION]\nmarket={}\nstatus={}\nreason={}\nraw_edge={}\npenalized_edge={}\nfinal_edge={}\nregime={}\nspread={}\nmid={}",
                    shortId(tokenId), decisionStatus, decisionReason,
                    fmt(rawEdge), fmt(penalizedEdge), fmt(finalEdge), regime, fmt(spread), fmt(mid));
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

            if (!hasBuy) {
                if (bidPrice.compareTo(MIN_PRICE) <= 0 || bidPrice.compareTo(MAX_PRICE) >= 0) {
                    logOrderReject(tokenId, "BUY", bidPrice, "price_out_of_range");
                } else {
                    double buyInvScale = computeInventorySideScale("BUY");
                    if (buyInvScale <= 0.0) {
                        log.debug("[BLOCKED]\nmarket={}\nside=BUY\nreason=inventory_hard_limit", shortId(tokenId));
                    } else {
                        BigDecimal buySize = finalSize.multiply(BigDecimal.valueOf(buyInvScale))
                                .setScale(0, RoundingMode.FLOOR)
                                .max(BigDecimal.valueOf(cfg.getMinOrderSize()));
                        ShadowOrder buy = fillModel.createHypotheticalOrder(
                                tokenId, s.getQuestion(), s.getOutcome(),
                                "BUY", bidPrice, buySize, s, finalEdge, capShare);
                        activeOrders.add(buy);
                        metrics.recordQuote();
                    }
                }
            }

            if (!hasSell) {
                if (askPrice.compareTo(MIN_PRICE) <= 0 || askPrice.compareTo(MAX_PRICE) >= 0) {
                    logOrderReject(tokenId, "SELL", askPrice, "price_out_of_range");
                } else {
                    double sellInvScale = computeInventorySideScale("SELL");
                    if (sellInvScale <= 0.0) {
                        log.debug("[BLOCKED]\nmarket={}\nside=SELL\nreason=inventory_hard_limit", shortId(tokenId));
                    } else {
                        BigDecimal sellSize = finalSize.multiply(BigDecimal.valueOf(sellInvScale))
                                .setScale(0, RoundingMode.FLOOR)
                                .max(BigDecimal.valueOf(cfg.getMinOrderSize()));
                        ShadowOrder sell = fillModel.createHypotheticalOrder(
                                tokenId, s.getQuestion(), s.getOutcome(),
                                "SELL", askPrice, sellSize, s, finalEdge, capShare);
                        activeOrders.add(sell);
                        metrics.recordQuote();
                    }
                }
            }
        }
    }

    private void logOrderReject(String tokenId, String side, BigDecimal proposedPrice, String reason) {
        orderRejectCount.incrementAndGet();
        log.info("[ORDER_REJECT]\nmarket={}\nside={}\nproposed_price={}\nreason={}",
                shortId(tokenId), side, proposedPrice.setScale(4, RoundingMode.HALF_UP), reason);
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
                    allocs.put(e.getKey(), e.getValue().add(bonus).min(maxCapShare));
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
                log.warn("[CAP_VIOLATION]\nmarket={}\nmarket_size={}\nmax_size={}",
                        shortId(e.getKey()), e.getValue().setScale(0, RoundingMode.HALF_UP),
                        maxSize.setScale(0, RoundingMode.HALF_UP));
            }
        }
    }

    // ── Counters ─────────────────────────────────────────────────────────

    private int countByRegime(Regime target) {
        return (int) liveMarkets.values().stream().filter(s -> s.getRegime() == target).count();
    }

    private int countActiveTokens() {
        Set<String> tokens = new HashSet<>();
        for (ShadowOrder o : activeOrders) tokens.add(o.getTokenId());
        return tokens.size();
    }

    // ── Structured Logging ───────────────────────────────────────────────

    private void logCycleSummary() {
        long cooled = cooldownUntilCycle.values().stream().filter(v -> v > cycleCount).count();

        int fillsNow = metrics.getTotalFills();
        BigDecimal pnlNow = metrics.getTotalPnl();
        BigDecimal feesNow = metrics.getTotalFees();

        String topMkt = metrics.getTopMarketId();
        BigDecimal topPnl = metrics.getTopMarketPnl();

        var worstList = metrics.getTopPnlMarkets(100);
        String worstMkt = "none";
        BigDecimal worstPnl = BigDecimal.ZERO;
        if (!worstList.isEmpty()) {
            var last = worstList.get(worstList.size() - 1);
            worstMkt = shortId(last.getKey());
            worstPnl = last.getValue().getPnl();
        }

        long guardSoft = guardService.getGuardStates().values().stream()
                .filter(g -> g.getStatus() == ShadowMarketGuardService.GuardStatus.SOFT_COOLDOWN).count();
        long guardHard = guardService.getGuardStates().values().stream()
                .filter(g -> g.getStatus() == ShadowMarketGuardService.GuardStatus.HARD_COOLDOWN).count();
        long guardDisabled = guardService.getGuardStates().values().stream()
                .filter(g -> g.getStatus() == ShadowMarketGuardService.GuardStatus.DISABLED_SESSION).count();

        log.info("[SHADOW_CYCLE_SUMMARY]\ncycle={}\nopen_orders={}\nfills_total={}\ntoxic_fills={}" +
                "\npnl_trading={}\npnl_reward=0.0000\npnl_total={}\nfees={}" +
                "\nyes_exposure={}\nno_exposure={}\nnet_exposure={}" +
                "\nmax_yes_exposure={}\nmax_no_exposure={}\nmax_net_exposure={}" +
                "\nactive_markets={}\ncooldown_markets={}\nvolatile_markets={}\ncrisis_markets={}" +
                "\ncap_violation_count={}" +
                "\nguard_soft_cooldown={}\nguard_hard_cooldown={}\nguard_disabled={}" +
                "\ntop_market={}\ntop_market_pnl={}" +
                "\nworst_market={}\nworst_market_pnl={}",
                cycleCount, activeOrders.size(),
                fillsNow, metrics.getToxicFills(),
                fmt(pnlNow), fmt(pnlNow),
                feesNow.setScale(6, RoundingMode.HALF_UP),
                fmt(getYesExposure()), fmt(getNoExposure()), fmt(getNetExposure()),
                fmt(metrics.getMaxYes()), fmt(metrics.getMaxNo()), fmt(metrics.getMaxNet()),
                countActiveTokens(), cooled,
                countByRegime(Regime.VOLATILE), countByRegime(Regime.CRISIS),
                capViolationCount.get(),
                guardSoft, guardHard, guardDisabled,
                shortId(topMkt), fmt(topPnl),
                worstMkt, fmt(worstPnl));

        if (fillsNow == 0 && fills.size() > 0) {
            log.warn("[METRICS_INCONSISTENCY] metrics.fills={} but fills.size={}", fillsNow, fills.size());
        }
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
        sb.append("cooldowns_triggered=").append(cooldownsTriggered.get()).append('\n');
        sb.append("order_rejects=").append(orderRejectCount.get()).append('\n');
        sb.append("cycles=").append(cycleCount).append('\n');
        sb.append("live_markets=").append(liveMarkets.size()).append('\n');
        sb.append("active_orders=").append(activeOrders.size()).append('\n');

        // Guard summary
        Map<String, Object> guardSummary = guardService.getGuardSummary();
        sb.append('\n');
        sb.append("GUARD:\n");
        sb.append("markets_tracked=").append(guardSummary.getOrDefault("marketsTracked", 0)).append('\n');
        sb.append("soft_cooldowns=").append(guardSummary.getOrDefault("softCooldownCount", 0)).append('\n');
        sb.append("hard_cooldowns=").append(guardSummary.getOrDefault("hardCooldownCount", 0)).append('\n');
        sb.append("disabled_session=").append(guardSummary.getOrDefault("disabledSessionCount", 0)).append('\n');
        sb.append("total_transitions=").append(guardSummary.getOrDefault("totalTransitions", 0)).append('\n');
        sb.append("hhi=").append(guardSummary.getOrDefault("hhi", "N/A")).append('\n');
        sb.append("top_fills_market=").append(guardSummary.getOrDefault("topFillsMarket", "none")).append('\n');
        sb.append("top_fills_share=").append(guardSummary.getOrDefault("topFillsShare", "0.0000")).append('\n');
        sb.append("estimated_pnl_saved=").append(guardSummary.getOrDefault("estimatedPnlSaved", "0.0000")).append('\n');

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
