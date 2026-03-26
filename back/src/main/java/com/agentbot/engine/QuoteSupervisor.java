package com.agentbot.engine;

import com.agentbot.engine.model.EngineOrder;
import com.agentbot.engine.model.EngineFill;
import com.agentbot.engine.model.SimulatedMarket;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

@Slf4j
@Component
@RequiredArgsConstructor
public class QuoteSupervisor {

    private static final Duration STALE_ORDER_AGE = Duration.ofSeconds(12);
    private static final BigDecimal BASE_FILL_PROBABILITY = new BigDecimal("0.03");
    private static final BigDecimal FEE_RATE = new BigDecimal("0.002");
    private static final BigDecimal OUTBID_THRESHOLD = new BigDecimal("0.015");

    private final OrderManager orderManager;
    private final PerformanceTracker performanceTracker;

    @Getter
    private final List<EngineFill> fills = new ArrayList<>();

    public void supervise(SimulatedMarket market) {
        List<EngineOrder> activeOrders = orderManager.getActiveOrdersForMarket(market.getMarketId());

        for (EngineOrder order : activeOrders) {
            if (isStale(order)) {
                orderManager.cancelOrder(order.getOrderId());
                continue;
            }

            if (shouldSimulateFill(order, market)) {
                simulateFill(order, market);
                continue;
            }

            if (isOutcompeted(order, market)) {
                orderManager.cancelOrder(order.getOrderId());
            }
        }
    }

    private boolean shouldSimulateFill(EngineOrder order, SimulatedMarket market) {
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        double prob = BASE_FILL_PROBABILITY.doubleValue();

        BigDecimal mid = market.getMidPrice();

        if (order.getSide() == EngineOrder.Side.BUY) {
            BigDecimal distFromMid = mid.subtract(order.getPrice());
            if (distFromMid.compareTo(BigDecimal.ZERO) > 0) {
                prob *= 0.7;
            } else {
                prob *= 1.8;
            }
            if (order.getPrice().compareTo(market.getBestBid()) > 0) {
                prob *= 1.3;
            }
        } else {
            BigDecimal distFromMid = order.getPrice().subtract(mid);
            if (distFromMid.compareTo(BigDecimal.ZERO) > 0) {
                prob *= 0.7;
            } else {
                prob *= 1.8;
            }
            if (order.getPrice().compareTo(market.getBestAsk()) < 0) {
                prob *= 1.3;
            }
        }

        if (market.isTrending()) {
            boolean trendAgainst = (order.getSide() == EngineOrder.Side.BUY
                    && market.getShortTermMomentum().compareTo(BigDecimal.ZERO) < 0)
                    || (order.getSide() == EngineOrder.Side.SELL
                    && market.getShortTermMomentum().compareTo(BigDecimal.ZERO) > 0);
            if (trendAgainst) {
                prob *= 1.5;
            } else {
                prob *= 0.5;
            }
        }

        return rng.nextDouble() < prob;
    }

    private void simulateFill(EngineOrder order, SimulatedMarket market) {
        ThreadLocalRandom rng = ThreadLocalRandom.current();

        BigDecimal fillSize;
        if (rng.nextDouble() < 0.75) {
            fillSize = order.getRemainingSize();
        } else {
            double fraction = rng.nextDouble(0.3, 0.7);
            fillSize = order.getRemainingSize()
                    .multiply(BigDecimal.valueOf(fraction))
                    .setScale(0, RoundingMode.HALF_UP)
                    .max(BigDecimal.ONE);
        }

        BigDecimal fee = fillSize.multiply(order.getPrice()).multiply(FEE_RATE)
                .setScale(4, RoundingMode.HALF_UP);

        EngineFill fill = EngineFill.builder()
                .fillId(UUID.randomUUID().toString().substring(0, 8))
                .orderId(order.getOrderId())
                .marketId(market.getMarketId())
                .marketName(market.getName())
                .side(order.getSide())
                .price(order.getPrice())
                .size(fillSize)
                .fee(fee)
                .filledAt(Instant.now())
                .build();

        fills.add(fill);
        orderManager.markFilled(order.getOrderId(), fillSize);
        performanceTracker.recordFill(fill, market);

        log.info("Fill: {} {} {} @ {} x{} (fee: {})",
                fill.getFillId(), order.getSide(), market.getName(),
                order.getPrice(), fillSize, fee);
    }

    private boolean isStale(EngineOrder order) {
        return Duration.between(order.getCreatedAt(), Instant.now()).compareTo(STALE_ORDER_AGE) > 0;
    }

    private boolean isOutcompeted(EngineOrder order, SimulatedMarket market) {
        if (order.getSide() == EngineOrder.Side.BUY) {
            return order.getPrice().compareTo(market.getBestBid().subtract(OUTBID_THRESHOLD)) < 0;
        } else {
            return order.getPrice().compareTo(market.getBestAsk().add(OUTBID_THRESHOLD)) > 0;
        }
    }
}
