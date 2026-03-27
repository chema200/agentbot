package com.agentbot.engine;

import com.agentbot.engine.model.EngineOrder;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;

@Slf4j
@Component
public class OrderManager {

    private static final long MIN_LATENCY_MS = 50;
    private static final long MAX_LATENCY_MS = 300;

    private final Map<String, EngineOrder> orders = new ConcurrentHashMap<>();

    @Getter
    private final List<EngineOrder> orderHistory = new ArrayList<>();

    @Getter
    @lombok.Setter
    private boolean backtestMode = false;

    public record MarketSnapshot(
            BigDecimal edgeScore, BigDecimal rewardEfficiency,
            BigDecimal competitionDensity, BigDecimal volatilityPenalty,
            BigDecimal capitalShare, BigDecimal spread,
            BigDecimal bestBid, BigDecimal bestAsk, BigDecimal mid,
            String regime) {}

    public EngineOrder createOrder(String marketId, String marketName,
                                    EngineOrder.Side side, BigDecimal price, BigDecimal size) {
        return createOrder(marketId, marketName, side, price, size, null);
    }

    public EngineOrder createOrder(String marketId, String marketName,
                                    EngineOrder.Side side, BigDecimal price, BigDecimal size,
                                    MarketSnapshot snapshot) {
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        long latency = backtestMode ? 0L : rng.nextLong(MIN_LATENCY_MS, MAX_LATENCY_MS);
        BigDecimal queueAhead = backtestMode ? BigDecimal.ZERO
                : size.multiply(BigDecimal.valueOf(rng.nextDouble(0.5, 3.0)))
                    .setScale(0, RoundingMode.HALF_UP);

        var builder = EngineOrder.builder()
                .orderId(UUID.randomUUID().toString().substring(0, 8))
                .marketId(marketId)
                .marketName(marketName)
                .side(side)
                .price(price)
                .originalSize(size)
                .remainingSize(size)
                .filledSize(BigDecimal.ZERO)
                .status(EngineOrder.Status.OPEN)
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .visibleAfter(backtestMode ? Instant.EPOCH : Instant.now().plusMillis(latency))
                .queuePosition(backtestMode ? 0 : rng.nextInt(3, 12))
                .queueAhead(queueAhead);

        if (snapshot != null) {
            builder.snapshotEdgeScore(snapshot.edgeScore())
                    .snapshotRewardEfficiency(snapshot.rewardEfficiency())
                    .snapshotCompetitionDensity(snapshot.competitionDensity())
                    .snapshotVolatilityPenalty(snapshot.volatilityPenalty())
                    .snapshotCapitalShare(snapshot.capitalShare())
                    .snapshotSpread(snapshot.spread())
                    .snapshotBestBid(snapshot.bestBid())
                    .snapshotBestAsk(snapshot.bestAsk())
                    .snapshotMid(snapshot.mid())
                    .snapshotRegime(snapshot.regime());
        }

        EngineOrder order = builder.build();
        orders.put(order.getOrderId(), order);
        log.debug("Created order {} {} {} @ {} x{} (latency: {}ms, queue: {})",
                order.getOrderId(), side, marketName, price, size, latency, queueAhead);
        return order;
    }

    public void cancelOrder(String orderId) {
        EngineOrder order = orders.get(orderId);
        if (order != null && order.isActive()) {
            order.cancel();
            orders.remove(orderId);
            orderHistory.add(order);
            log.debug("Cancelled order {}", orderId);
        }
    }

    public EngineOrder replaceOrder(String oldOrderId, BigDecimal newPrice, BigDecimal newSize) {
        EngineOrder old = orders.get(oldOrderId);
        if (old == null) return null;

        cancelOrder(oldOrderId);
        return createOrder(old.getMarketId(), old.getMarketName(), old.getSide(), newPrice, newSize);
    }

    public List<EngineOrder> getActiveOrders() {
        return orders.values().stream()
                .filter(EngineOrder::isActive)
                .collect(Collectors.toList());
    }

    public List<EngineOrder> getActiveOrdersForMarket(String marketId) {
        return orders.values().stream()
                .filter(o -> o.isActive() && o.getMarketId().equals(marketId))
                .collect(Collectors.toList());
    }

    public List<EngineOrder> getAllOrders() {
        List<EngineOrder> all = new ArrayList<>(orders.values());
        all.addAll(orderHistory);
        return all;
    }

    public EngineOrder getOrder(String orderId) {
        EngineOrder o = orders.get(orderId);
        if (o != null) return o;
        return orderHistory.stream()
                .filter(h -> h.getOrderId().equals(orderId))
                .findFirst().orElse(null);
    }

    public void markFilled(String orderId, BigDecimal filledQty) {
        EngineOrder order = orders.get(orderId);
        if (order != null) {
            order.fill(filledQty);
            if (!order.isActive()) {
                orders.remove(orderId);
                orderHistory.add(order);
            }
        }
    }

    public int activeOrderCount() {
        return (int) orders.values().stream().filter(EngineOrder::isActive).count();
    }

    public int activeOrderCountForMarket(String marketId) {
        return (int) orders.values().stream()
                .filter(o -> o.isActive() && o.getMarketId().equals(marketId))
                .count();
    }

    public void reset() {
        orders.clear();
        orderHistory.clear();
        backtestMode = false;
    }
}
