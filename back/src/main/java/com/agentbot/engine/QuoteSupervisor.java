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

    private static final Duration STALE_ORDER_AGE = Duration.ofSeconds(20);
    private static final BigDecimal FEE_RATE = new BigDecimal("0.002");
    private static final BigDecimal OUTBID_THRESHOLD = new BigDecimal("0.015");

    private final OrderManager orderManager;
    private final PerformanceTracker performanceTracker;
    private final MarketDataEngine marketDataEngine;

    @Getter
    private final List<EngineFill> fills = new ArrayList<>();

    public void supervise(SimulatedMarket market) {
        List<EngineOrder> activeOrders = orderManager.getActiveOrdersForMarket(market.getMarketId());

        drainQueues(activeOrders, market);

        for (EngineOrder order : activeOrders) {
            if (!order.isVisible()) continue;

            if (isStale(order)) {
                orderManager.cancelOrder(order.getOrderId());
                continue;
            }

            if (isOutcompeted(order, market)) {
                orderManager.cancelOrder(order.getOrderId());
                continue;
            }

            if (order.isQueueCleared() && shouldFill(order, market)) {
                executeFill(order, market);
            }
        }
    }

    private void drainQueues(List<EngineOrder> orders, SimulatedMarket market) {
        BigDecimal tickVol = market.getTickVolume();
        if (tickVol.compareTo(BigDecimal.ZERO) <= 0) return;

        for (EngineOrder order : orders) {
            if (!order.isActive() || order.isQueueCleared()) continue;

            BigDecimal drain = tickVol.multiply(new BigDecimal("0.5"))
                    .setScale(0, RoundingMode.HALF_UP);
            order.drainQueue(drain);
        }
    }

    private boolean shouldFill(EngineOrder order, SimulatedMarket market) {
        ThreadLocalRandom rng = ThreadLocalRandom.current();

        double prob = calculateFillProbability(order, market);

        if (market.isInformedFlowActive()) {
            boolean adverseForUs = isAdverseFill(order, market);
            if (adverseForUs) {
                prob *= 3.0;
            } else {
                prob *= 0.2;
            }
        }

        if (market.isCrisis()) {
            prob *= 0.5;
        }

        return rng.nextDouble() < prob;
    }

    private double calculateFillProbability(EngineOrder order, SimulatedMarket market) {
        BigDecimal mid = market.getMidPrice();
        BigDecimal distance;

        if (order.getSide() == EngineOrder.Side.BUY) {
            distance = mid.subtract(order.getPrice());
        } else {
            distance = order.getPrice().subtract(mid);
        }

        double distanceFactor;
        double distVal = distance.doubleValue();
        if (distVal <= 0) {
            distanceFactor = 0.10;
        } else if (distVal <= 0.01) {
            distanceFactor = 0.07;
        } else if (distVal <= 0.02) {
            distanceFactor = 0.04;
        } else if (distVal <= 0.03) {
            distanceFactor = 0.025;
        } else {
            distanceFactor = 0.01;
        }

        double activityFactor = 1.0;
        double tickVol = market.getTickVolume().doubleValue();
        if (tickVol > 400) activityFactor = 1.4;
        else if (tickVol > 200) activityFactor = 1.1;
        else if (tickVol < 100) activityFactor = 0.7;

        double regimeFactor = switch (market.getRegime()) {
            case CALM -> 0.8;
            case NORMAL -> 1.0;
            case VOLATILE -> 1.3;
            case CRISIS -> 0.6;
        };

        double competitionFactor = 1.0;
        if (order.getSide() == EngineOrder.Side.BUY) {
            if (order.getPrice().compareTo(market.getCompetitorBestBid()) < 0) {
                competitionFactor = 0.4;
            }
        } else {
            if (order.getPrice().compareTo(market.getCompetitorBestAsk()) > 0) {
                competitionFactor = 0.4;
            }
        }

        return distanceFactor * activityFactor * regimeFactor * competitionFactor;
    }

    private boolean isAdverseFill(EngineOrder order, SimulatedMarket market) {
        BigDecimal dir = market.getInformedDirection();
        if (order.getSide() == EngineOrder.Side.BUY && dir.compareTo(BigDecimal.ZERO) < 0) return true;
        if (order.getSide() == EngineOrder.Side.SELL && dir.compareTo(BigDecimal.ZERO) > 0) return true;
        return false;
    }

    private void executeFill(EngineOrder order, SimulatedMarket market) {
        ThreadLocalRandom rng = ThreadLocalRandom.current();

        BigDecimal fillSize;
        if (rng.nextDouble() < 0.70) {
            fillSize = order.getRemainingSize();
        } else {
            double fraction = rng.nextDouble(0.2, 0.6);
            fillSize = order.getRemainingSize()
                    .multiply(BigDecimal.valueOf(fraction))
                    .setScale(0, RoundingMode.HALF_UP)
                    .max(BigDecimal.ONE);
        }

        BigDecimal fee = fillSize.multiply(order.getPrice()).multiply(FEE_RATE)
                .setScale(4, RoundingMode.HALF_UP);

        BigDecimal midAtFill = market.getMidPrice();
        BigDecimal slippage;
        if (order.getSide() == EngineOrder.Side.BUY) {
            slippage = order.getPrice().subtract(midAtFill);
        } else {
            slippage = midAtFill.subtract(order.getPrice());
        }

        boolean toxic = market.isInformedFlowActive() && isAdverseFill(order, market);

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
                .midAtFill(midAtFill)
                .slippage(slippage)
                .toxicFlow(toxic)
                .build();

        fills.add(fill);
        orderManager.markFilled(order.getOrderId(), fillSize);
        performanceTracker.recordFill(fill, market);

        applyPostFillImpact(order, market, fillSize, rng);

        String toxicTag = toxic ? " [TOXIC]" : "";
        log.info("Fill: {} {} {} @ {} x{} (slip: {}, fee: {}){}",
                fill.getFillId(), order.getSide(), market.getName(),
                order.getPrice(), fillSize, slippage, fee, toxicTag);
    }

    private void applyPostFillImpact(EngineOrder order, SimulatedMarket market,
                                      BigDecimal fillSize, ThreadLocalRandom rng) {
        BigDecimal direction;
        if (order.getSide() == EngineOrder.Side.BUY) {
            direction = BigDecimal.ONE;
        } else {
            direction = BigDecimal.ONE.negate();
        }

        BigDecimal baseMagnitude = fillSize.divide(new BigDecimal("200"), 4, RoundingMode.HALF_UP);
        BigDecimal noise = BigDecimal.valueOf(rng.nextGaussian() * 0.3);
        BigDecimal magnitude = baseMagnitude.add(noise).max(BigDecimal.ZERO);

        if (market.isInformedFlowActive()) {
            magnitude = magnitude.multiply(new BigDecimal("2.0"));
        }

        marketDataEngine.applyPostFillImpact(market, direction, magnitude);
    }

    private boolean isStale(EngineOrder order) {
        return Duration.between(order.getCreatedAt(), Instant.now()).compareTo(STALE_ORDER_AGE) > 0;
    }

    private boolean isOutcompeted(EngineOrder order, SimulatedMarket market) {
        if (order.getSide() == EngineOrder.Side.BUY) {
            return order.getPrice().compareTo(market.getCompetitorBestBid().subtract(OUTBID_THRESHOLD)) < 0;
        } else {
            return order.getPrice().compareTo(market.getCompetitorBestAsk().add(OUTBID_THRESHOLD)) > 0;
        }
    }

    public void reset() {
        fills.clear();
    }
}
