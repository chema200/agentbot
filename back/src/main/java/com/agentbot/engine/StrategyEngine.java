package com.agentbot.engine;

import com.agentbot.engine.model.EngineOrder;
import com.agentbot.engine.model.InventoryPosition;
import com.agentbot.engine.model.SimulatedMarket;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class StrategyEngine {

    private static final BigDecimal BASE_ORDER_SIZE = new BigDecimal("50");
    private static final BigDecimal MIN_ORDER_SIZE = new BigDecimal("10");
    private static final BigDecimal MAX_INVENTORY_FOR_FULL_SIZE = new BigDecimal("200");
    private static final BigDecimal INVENTORY_SKEW_FACTOR = new BigDecimal("0.02");
    private static final BigDecimal STRONG_SKEW_THRESHOLD = new BigDecimal("150");
    private static final BigDecimal SAFETY_BUFFER = new BigDecimal("0.005");
    private static final BigDecimal MIN_QUOTE_PRICE = new BigDecimal("0.02");
    private static final BigDecimal MAX_QUOTE_PRICE = new BigDecimal("0.98");
    private static final BigDecimal MAX_HALF_SPREAD = new BigDecimal("0.10");
    private static final BigDecimal MIN_HALF_SPREAD = new BigDecimal("0.01");

    private final OrderManager orderManager;
    private final PerformanceTracker performanceTracker;

    public void executeStrategy(SimulatedMarket market, InventoryPosition inventory,
                                boolean riskAllowed, int maxOrdersPerSide) {
        if (!riskAllowed) {
            return;
        }

        if (market.isTrending()) {
            log.debug("Skipping {} - momentum detected: {}", market.getMarketId(), market.getShortTermMomentum());
            return;
        }

        if (market.isHighVolatility()) {
            log.debug("Reducing activity for {} - high realized vol", market.getMarketId());
            maxOrdersPerSide = 1;
        }

        List<EngineOrder> activeOrders = orderManager.getActiveOrdersForMarket(market.getMarketId());
        long buyCount = activeOrders.stream().filter(o -> o.getSide() == EngineOrder.Side.BUY).count();
        long sellCount = activeOrders.stream().filter(o -> o.getSide() == EngineOrder.Side.SELL).count();

        BigDecimal inventorySkew = calculateInventorySkew(inventory);
        BigDecimal adaptiveHalfSpread = calculateAdaptiveHalfSpread(market);
        BigDecimal orderSize = calculateOrderSize(inventory);

        boolean buyAllowed = shouldAllowBuy(inventory);
        boolean sellAllowed = shouldAllowSell(inventory);

        if (buyAllowed && buyCount < maxOrdersPerSide) {
            BigDecimal rawBid = market.getMidPrice()
                    .subtract(adaptiveHalfSpread)
                    .subtract(SAFETY_BUFFER)
                    .subtract(inventorySkew);
            BigDecimal behindTouchBid = market.getBestBid().subtract(SAFETY_BUFFER);
            BigDecimal bidPrice = rawBid.min(behindTouchBid).setScale(2, RoundingMode.HALF_UP);

            if (bidPrice.compareTo(MIN_QUOTE_PRICE) > 0
                    && bidPrice.compareTo(market.getBestBid()) <= 0
                    && bidPrice.compareTo(market.getBestAsk()) < 0) {
                orderManager.createOrder(
                        market.getMarketId(), market.getName(),
                        EngineOrder.Side.BUY, bidPrice, orderSize);
            }
        }

        if (sellAllowed && sellCount < maxOrdersPerSide) {
            BigDecimal rawAsk = market.getMidPrice()
                    .add(adaptiveHalfSpread)
                    .add(SAFETY_BUFFER)
                    .add(inventorySkew);
            BigDecimal behindTouchAsk = market.getBestAsk().add(SAFETY_BUFFER);
            BigDecimal askPrice = rawAsk.max(behindTouchAsk).setScale(2, RoundingMode.HALF_UP);

            if (askPrice.compareTo(MAX_QUOTE_PRICE) < 0
                    && askPrice.compareTo(market.getBestAsk()) >= 0
                    && askPrice.compareTo(market.getBestBid()) > 0) {
                orderManager.createOrder(
                        market.getMarketId(), market.getName(),
                        EngineOrder.Side.SELL, askPrice, orderSize);
            }
        }
    }

    /**
     * Half-spread away from mid, scaled by realized and structural volatility, adverse selection,
     * fill intensity, and performance-driven widening.
     */
    private BigDecimal calculateAdaptiveHalfSpread(SimulatedMarket market) {
        String marketId = market.getMarketId();

        BigDecimal baseHalfSpread = market.getSpread()
                .divide(BigDecimal.TWO, 4, RoundingMode.HALF_UP)
                .max(MIN_HALF_SPREAD);

        BigDecimal realized = market.getRealizedVolatility() != null
                ? market.getRealizedVolatility()
                : BigDecimal.ZERO;
        BigDecimal structural = market.getVolatility() != null
                ? market.getVolatility()
                : MIN_HALF_SPREAD;
        BigDecimal volForMultiplier = realized.max(structural);
        BigDecimal volMultiplier = BigDecimal.ONE.add(volForMultiplier.multiply(new BigDecimal("5")));

        BigDecimal perfAdjustment = performanceTracker.getSpreadAdjustment(marketId);

        if (performanceTracker.shouldWidenSpread(marketId)) {
            perfAdjustment = perfAdjustment.add(new BigDecimal("0.008"));
        }

        double adverseRate = performanceTracker.getAdverseSelectionRate(marketId);
        BigDecimal adverseFineTune = BigDecimal.ZERO;
        if (adverseRate > 0.25 && adverseRate <= 0.4) {
            adverseFineTune = new BigDecimal("0.004");
        } else if (adverseRate > 0.4 && adverseRate <= 0.5) {
            adverseFineTune = new BigDecimal("0.008");
        }

        int totalFills = performanceTracker.getTotalFills(marketId);
        BigDecimal fillIntensityWiden = BigDecimal.ZERO;
        if (totalFills >= 80) {
            fillIntensityWiden = new BigDecimal("0.006");
        } else if (totalFills >= 40) {
            fillIntensityWiden = new BigDecimal("0.003");
        }

        BigDecimal result = baseHalfSpread
                .multiply(volMultiplier)
                .add(perfAdjustment)
                .add(adverseFineTune)
                .add(fillIntensityWiden)
                .max(MIN_HALF_SPREAD)
                .min(MAX_HALF_SPREAD);

        return result.setScale(4, RoundingMode.HALF_UP);
    }

    private BigDecimal calculateInventorySkew(InventoryPosition inventory) {
        if (inventory == null) {
            return BigDecimal.ZERO;
        }
        BigDecimal net = inventory.getNetExposure();
        BigDecimal absNet = net.abs();

        BigDecimal skew = net.multiply(INVENTORY_SKEW_FACTOR);

        if (absNet.compareTo(STRONG_SKEW_THRESHOLD) > 0) {
            BigDecimal extraSkew = absNet.subtract(STRONG_SKEW_THRESHOLD)
                    .multiply(new BigDecimal("0.0001"));
            skew = net.signum() > 0
                    ? skew.add(extraSkew)
                    : skew.subtract(extraSkew);
        }

        return skew.setScale(4, RoundingMode.HALF_UP);
    }

    private BigDecimal calculateOrderSize(InventoryPosition inventory) {
        if (inventory == null) {
            return BASE_ORDER_SIZE.setScale(0, RoundingMode.HALF_UP);
        }

        BigDecimal totalExposure = inventory.getTotalExposure();
        if (totalExposure.compareTo(MAX_INVENTORY_FOR_FULL_SIZE) > 0) {
            BigDecimal ratio = MAX_INVENTORY_FOR_FULL_SIZE
                    .divide(totalExposure.max(BigDecimal.ONE), 4, RoundingMode.HALF_UP);
            BigDecimal reduced = BASE_ORDER_SIZE.multiply(ratio);
            return reduced.max(MIN_ORDER_SIZE).setScale(0, RoundingMode.HALF_UP);
        }
        return BASE_ORDER_SIZE.setScale(0, RoundingMode.HALF_UP);
    }

    private boolean shouldAllowBuy(InventoryPosition inventory) {
        if (inventory == null) {
            return true;
        }
        return inventory.getNetExposure().compareTo(new BigDecimal("300")) < 0;
    }

    private boolean shouldAllowSell(InventoryPosition inventory) {
        if (inventory == null) {
            return true;
        }
        return inventory.getNetExposure().compareTo(new BigDecimal("-300")) > 0;
    }
}
