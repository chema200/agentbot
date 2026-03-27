package com.agentbot.engine;

import com.agentbot.engine.model.EngineOrder;
import com.agentbot.engine.model.InventoryPosition;
import com.agentbot.engine.model.MarketScore;
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
    private static final BigDecimal MAX_ORDER_SIZE = new BigDecimal("200");
    private static final BigDecimal MAX_INVENTORY_FOR_FULL_SIZE = new BigDecimal("200");
    private static final BigDecimal INVENTORY_SKEW_FACTOR = new BigDecimal("0.02");
    private static final BigDecimal STRONG_SKEW_THRESHOLD = new BigDecimal("150");
    private static final BigDecimal SAFETY_BUFFER = new BigDecimal("0.005");
    private static final BigDecimal MIN_QUOTE_PRICE = new BigDecimal("0.02");
    private static final BigDecimal MAX_QUOTE_PRICE = new BigDecimal("0.98");
    private static final BigDecimal MAX_HALF_SPREAD = new BigDecimal("0.10");
    private static final BigDecimal MIN_HALF_SPREAD = new BigDecimal("0.01");
    private static final BigDecimal TICK = new BigDecimal("0.01");
    private static final BigDecimal MAX_INVENTORY_NET = new BigDecimal("300");

    private final OrderManager orderManager;
    private final PerformanceTracker performanceTracker;
    private final PnLService pnlService;

    public void executeStrategy(SimulatedMarket market, InventoryPosition inventory,
                                boolean riskAllowed, int maxOrdersPerSide,
                                MarketScore scored, BigDecimal capitalShare) {
        if (!riskAllowed) return;

        if (market.isCrisis() || market.isInformedFlowActive() || market.isTrending()) {
            return;
        }

        if (market.isHighVolatility()) {
            maxOrdersPerSide = 1;
        }

        List<EngineOrder> activeOrders = orderManager.getActiveOrdersForMarket(market.getMarketId());
        long buyCount = activeOrders.stream().filter(o -> o.getSide() == EngineOrder.Side.BUY).count();
        long sellCount = activeOrders.stream().filter(o -> o.getSide() == EngineOrder.Side.SELL).count();

        BigDecimal edgeScore = scored != null ? scored.getEdgeScore() : BigDecimal.ZERO;
        BigDecimal rewardEfficiency = scored != null && scored.getRewardEfficiency() != null
                ? scored.getRewardEfficiency() : BigDecimal.ZERO;

        BigDecimal inventorySkew = calculateInventorySkew(inventory);
        BigDecimal adaptiveHalfSpread = calculateAdaptiveHalfSpread(market, edgeScore, rewardEfficiency);
        BigDecimal orderSize = calculateDynamicSize(inventory, edgeScore, market, capitalShare);

        boolean buyAllowed = shouldAllowBuy(inventory);
        boolean sellAllowed = shouldAllowSell(inventory);

        BigDecimal aggressionOffset = calculateAggressionOffset(edgeScore, rewardEfficiency, market);

        OrderManager.MarketSnapshot snapshot = buildSnapshot(market, scored, capitalShare);

        if (buyAllowed && buyCount < maxOrdersPerSide) {
            BigDecimal rawBid = market.getMidPrice()
                    .subtract(adaptiveHalfSpread)
                    .subtract(SAFETY_BUFFER)
                    .subtract(inventorySkew)
                    .add(aggressionOffset);
            BigDecimal behindCompetitor = market.getCompetitorBestBid().subtract(TICK);
            BigDecimal behindTouchBid = market.getBestBid().subtract(SAFETY_BUFFER);
            BigDecimal bidPrice = rawBid.min(behindTouchBid).min(behindCompetitor).setScale(2, RoundingMode.HALF_UP);

            if (bidPrice.compareTo(MIN_QUOTE_PRICE) > 0
                    && bidPrice.compareTo(market.getBestAsk()) < 0) {
                orderManager.createOrder(
                        market.getMarketId(), market.getName(),
                        EngineOrder.Side.BUY, bidPrice, orderSize, snapshot);
            }
        }

        if (sellAllowed && sellCount < maxOrdersPerSide) {
            BigDecimal rawAsk = market.getMidPrice()
                    .add(adaptiveHalfSpread)
                    .add(SAFETY_BUFFER)
                    .add(inventorySkew)
                    .subtract(aggressionOffset);
            BigDecimal behindCompetitor = market.getCompetitorBestAsk().add(TICK);
            BigDecimal behindTouchAsk = market.getBestAsk().add(SAFETY_BUFFER);
            BigDecimal askPrice = rawAsk.max(behindTouchAsk).max(behindCompetitor).setScale(2, RoundingMode.HALF_UP);

            if (askPrice.compareTo(MAX_QUOTE_PRICE) < 0
                    && askPrice.compareTo(market.getBestBid()) > 0) {
                orderManager.createOrder(
                        market.getMarketId(), market.getName(),
                        EngineOrder.Side.SELL, askPrice, orderSize, snapshot);
            }
        }
    }

    /**
     * size = base * edgeFactor * capitalShare * inventoryFactor * regimeFactor
     * Capped at MAX_ORDER_SIZE, floored at MIN_ORDER_SIZE.
     */
    private BigDecimal calculateDynamicSize(InventoryPosition inventory, BigDecimal edgeScore,
                                            SimulatedMarket market, BigDecimal capitalShare) {
        double edge = edgeScore.doubleValue();
        double edgeFactor = Math.max(0.3, Math.min(2.5, 1.0 + edge * 0.8));

        double capFactor = capitalShare != null
                ? Math.max(0.3, Math.min(2.0, capitalShare.doubleValue() * 5.0))
                : 1.0;

        double inventoryFactor = 1.0;
        if (inventory != null) {
            BigDecimal totalExposure = inventory.getTotalExposure();
            if (totalExposure.compareTo(MAX_INVENTORY_FOR_FULL_SIZE) > 0) {
                double ratio = MAX_INVENTORY_FOR_FULL_SIZE.doubleValue()
                        / Math.max(1.0, totalExposure.doubleValue());
                inventoryFactor = Math.max(0.2, ratio);
            }

            BigDecimal absNet = inventory.getNetExposure().abs();
            if (absNet.compareTo(new BigDecimal("100")) > 0) {
                double netPenalty = 1.0 - (absNet.doubleValue() - 100.0) / 600.0;
                inventoryFactor *= Math.max(0.3, netPenalty);
            }
        }

        double regimeFactor = switch (market.getRegime()) {
            case CALM -> 1.2;
            case NORMAL -> 1.0;
            case VOLATILE -> 0.5;
            case CRISIS -> 0.1;
        };

        double recentPnlFactor = calculateRecentPnlFactor(market.getMarketId());

        double rawSize = BASE_ORDER_SIZE.doubleValue() * edgeFactor * capFactor
                * inventoryFactor * regimeFactor * recentPnlFactor;

        BigDecimal size = BigDecimal.valueOf(rawSize).setScale(0, RoundingMode.HALF_UP);
        return size.max(MIN_ORDER_SIZE).min(MAX_ORDER_SIZE);
    }

    private double calculateRecentPnlFactor(String marketId) {
        BigDecimal marketPnl = pnlService.getRealizedForMarket(marketId);
        int fills = performanceTracker.getTotalFills(marketId);
        if (fills < 3) return 1.0;

        double pnlPerFill = marketPnl.doubleValue() / fills;
        if (pnlPerFill < -0.5) return 0.4;
        if (pnlPerFill < -0.1) return 0.7;
        if (pnlPerFill > 1.0) return 1.3;
        if (pnlPerFill > 0.3) return 1.15;
        return 1.0;
    }

    /**
     * Aggression offset: moves quotes closer to mid when edge is high,
     * and further from mid when edge is low or competition is intense.
     */
    private BigDecimal calculateAggressionOffset(BigDecimal edgeScore, BigDecimal rewardEfficiency,
                                                  SimulatedMarket market) {
        double edge = edgeScore.doubleValue();
        double rwdEff = rewardEfficiency.doubleValue();

        if (edge > 1.5 && rwdEff > 0.001) {
            return TICK.multiply(new BigDecimal("1.5"));
        }
        if (edge > 0.8 && rwdEff > 0.0005) {
            return TICK;
        }

        if (market.getCompetitionLevel().compareTo(new BigDecimal("0.7")) > 0) {
            return TICK.negate();
        }

        return BigDecimal.ZERO;
    }

    private BigDecimal calculateAdaptiveHalfSpread(SimulatedMarket market, BigDecimal edgeScore,
                                                    BigDecimal rewardEfficiency) {
        String marketId = market.getMarketId();

        BigDecimal baseHalfSpread = market.getSpread()
                .divide(BigDecimal.TWO, 4, RoundingMode.HALF_UP)
                .max(MIN_HALF_SPREAD);

        BigDecimal realized = market.getRealizedVolatility() != null
                ? market.getRealizedVolatility() : BigDecimal.ZERO;
        BigDecimal structural = market.getVolatility() != null
                ? market.getVolatility() : MIN_HALF_SPREAD;
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
        } else if (adverseRate > 0.4) {
            adverseFineTune = new BigDecimal("0.008");
        }

        BigDecimal regimeWiden = market.getVolatilityMultiplier()
                .subtract(BigDecimal.ONE).max(BigDecimal.ZERO)
                .multiply(new BigDecimal("0.01"));

        double edge = edgeScore.doubleValue();
        BigDecimal edgeAdjust = BigDecimal.ZERO;
        if (edge > 1.5) {
            edgeAdjust = new BigDecimal("-0.003");
        } else if (edge > 0.8) {
            edgeAdjust = new BigDecimal("-0.001");
        } else if (edge < 0.2) {
            edgeAdjust = new BigDecimal("0.005");
        }

        double rwdEff = rewardEfficiency.doubleValue();
        BigDecimal rewardAdjust = BigDecimal.ZERO;
        if (rwdEff > 0.002) {
            rewardAdjust = new BigDecimal("-0.002");
        } else if (rwdEff > 0.001) {
            rewardAdjust = new BigDecimal("-0.001");
        }

        BigDecimal fillQualityAdjust = BigDecimal.ZERO;
        double profitPerFill = performanceTracker.getProfitPerFill(marketId);
        if (profitPerFill < -0.5) {
            fillQualityAdjust = new BigDecimal("0.008");
        } else if (profitPerFill < 0) {
            fillQualityAdjust = new BigDecimal("0.003");
        }

        BigDecimal result = baseHalfSpread
                .multiply(volMultiplier)
                .add(perfAdjustment)
                .add(adverseFineTune)
                .add(regimeWiden)
                .add(edgeAdjust)
                .add(rewardAdjust)
                .add(fillQualityAdjust)
                .max(MIN_HALF_SPREAD)
                .min(MAX_HALF_SPREAD);

        return result.setScale(4, RoundingMode.HALF_UP);
    }

    private BigDecimal calculateInventorySkew(InventoryPosition inventory) {
        if (inventory == null) return BigDecimal.ZERO;
        BigDecimal net = inventory.getNetExposure();
        BigDecimal absNet = net.abs();

        BigDecimal skew = net.multiply(INVENTORY_SKEW_FACTOR);

        if (absNet.compareTo(STRONG_SKEW_THRESHOLD) > 0) {
            BigDecimal extraSkew = absNet.subtract(STRONG_SKEW_THRESHOLD)
                    .multiply(new BigDecimal("0.0001"));
            skew = net.signum() > 0 ? skew.add(extraSkew) : skew.subtract(extraSkew);
        }

        return skew.setScale(4, RoundingMode.HALF_UP);
    }

    private boolean shouldAllowBuy(InventoryPosition inventory) {
        if (inventory == null) return true;
        return inventory.getNetExposure().compareTo(MAX_INVENTORY_NET) < 0;
    }

    private boolean shouldAllowSell(InventoryPosition inventory) {
        if (inventory == null) return true;
        return inventory.getNetExposure().compareTo(MAX_INVENTORY_NET.negate()) > 0;
    }

    private OrderManager.MarketSnapshot buildSnapshot(SimulatedMarket market,
                                                       MarketScore scored, BigDecimal capitalShare) {
        return new OrderManager.MarketSnapshot(
                scored != null ? scored.getEdgeScore() : BigDecimal.ZERO,
                scored != null && scored.getRewardEfficiency() != null ? scored.getRewardEfficiency() : BigDecimal.ZERO,
                scored != null && scored.getCompetitionDensity() != null ? scored.getCompetitionDensity() : BigDecimal.ZERO,
                scored != null && scored.getVolatilityPenalty() != null ? scored.getVolatilityPenalty() : BigDecimal.ZERO,
                capitalShare != null ? capitalShare : BigDecimal.ZERO,
                market.getSpread(),
                market.getBestBid(),
                market.getBestAsk(),
                market.getMidPrice(),
                market.getRegime().name()
        );
    }
}
