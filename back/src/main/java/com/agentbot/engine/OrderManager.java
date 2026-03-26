package com.agentbot.engine;

import com.agentbot.engine.model.EngineOrder;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Slf4j
@Component
public class OrderManager {

    private final Map<String, EngineOrder> orders = new ConcurrentHashMap<>();

    @Getter
    private final List<EngineOrder> orderHistory = new ArrayList<>();

    public EngineOrder createOrder(String marketId, String marketName,
                                    EngineOrder.Side side, BigDecimal price, BigDecimal size) {
        EngineOrder order = EngineOrder.builder()
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
                .build();

        orders.put(order.getOrderId(), order);
        log.debug("Created order {} {} {} @ {} x{}", order.getOrderId(), side, marketName, price, size);
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
        return orders.get(orderId);
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
}
