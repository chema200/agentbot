package com.agentbot.engine;

import com.agentbot.engine.model.EngineOrder;
import com.agentbot.engine.model.EngineFill;
import com.agentbot.engine.model.SimulatedMarket;
import com.agentbot.engine.model.InventoryPosition;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
public class PnLService {

    @Getter
    private BigDecimal totalRealizedPnl = BigDecimal.ZERO;

    @Getter
    private BigDecimal totalFees = BigDecimal.ZERO;

    @Getter
    private BigDecimal totalRewardPnl = BigDecimal.ZERO;

    private final Map<String, BigDecimal> realizedByMarket = new ConcurrentHashMap<>();
    private final Map<String, BigDecimal> rewardByMarket = new ConcurrentHashMap<>();
    private final Map<String, BigDecimal> yesCostBasis = new ConcurrentHashMap<>();
    private final Map<String, BigDecimal> yesQtyBasis = new ConcurrentHashMap<>();

    public void recordFill(EngineFill fill) {
        totalFees = totalFees.add(fill.getFee());

        String mktId = fill.getMarketId();
        BigDecimal realized = BigDecimal.ZERO;

        if (fill.getSide() == EngineOrder.Side.BUY) {
            BigDecimal prevCost = yesCostBasis.getOrDefault(mktId, BigDecimal.ZERO);
            BigDecimal prevQty = yesQtyBasis.getOrDefault(mktId, BigDecimal.ZERO);
            yesCostBasis.put(mktId, prevCost.add(fill.getPrice().multiply(fill.getSize())));
            yesQtyBasis.put(mktId, prevQty.add(fill.getSize()));
            realized = fill.getFee().negate();
        } else {
            BigDecimal avgCost = getAvgCost(mktId);
            BigDecimal grossPnl = fill.getPrice().subtract(avgCost).multiply(fill.getSize());
            realized = grossPnl.subtract(fill.getFee());

            BigDecimal prevQty = yesQtyBasis.getOrDefault(mktId, BigDecimal.ZERO);
            BigDecimal sellQty = fill.getSize().min(prevQty);
            if (sellQty.compareTo(BigDecimal.ZERO) > 0 && prevQty.compareTo(BigDecimal.ZERO) > 0) {
                BigDecimal costReduction = avgCost.multiply(sellQty);
                yesCostBasis.put(mktId, yesCostBasis.getOrDefault(mktId, BigDecimal.ZERO).subtract(costReduction));
                yesQtyBasis.put(mktId, prevQty.subtract(sellQty));
            }
        }

        totalRealizedPnl = totalRealizedPnl.add(realized);
        realizedByMarket.merge(mktId, realized, BigDecimal::add);
    }

    private BigDecimal getAvgCost(String marketId) {
        BigDecimal cost = yesCostBasis.getOrDefault(marketId, BigDecimal.ZERO);
        BigDecimal qty = yesQtyBasis.getOrDefault(marketId, BigDecimal.ZERO);
        if (qty.compareTo(BigDecimal.ZERO) <= 0) return BigDecimal.ZERO;
        return cost.divide(qty, 6, RoundingMode.HALF_UP);
    }

    public BigDecimal getUnrealizedPnl(Collection<InventoryPosition> positions,
                                        java.util.function.Function<String, SimulatedMarket> marketLookup) {
        BigDecimal unrealized = BigDecimal.ZERO;
        for (InventoryPosition pos : positions) {
            SimulatedMarket market = marketLookup.apply(pos.getMarketId());
            if (market == null) continue;

            BigDecimal avgCost = getAvgCost(pos.getMarketId());
            if (pos.getYesQuantity().compareTo(BigDecimal.ZERO) > 0 && avgCost.compareTo(BigDecimal.ZERO) > 0) {
                unrealized = unrealized.add(
                        market.getMidPrice().subtract(avgCost).multiply(pos.getYesQuantity()));
            }
        }
        return unrealized.setScale(4, RoundingMode.HALF_UP);
    }

    public BigDecimal getRealizedForMarket(String marketId) {
        return realizedByMarket.getOrDefault(marketId, BigDecimal.ZERO);
    }

    public Map<String, BigDecimal> getAllMarketPnl() {
        return Map.copyOf(realizedByMarket);
    }

    public void recordReward(String marketId, BigDecimal reward) {
        if (reward.compareTo(BigDecimal.ZERO) <= 0) return;
        totalRewardPnl = totalRewardPnl.add(reward);
        rewardByMarket.merge(marketId, reward, BigDecimal::add);
    }

    public BigDecimal getRewardForMarket(String marketId) {
        return rewardByMarket.getOrDefault(marketId, BigDecimal.ZERO);
    }

    public BigDecimal getTotalPnl() {
        return totalRealizedPnl.add(totalRewardPnl);
    }

    public BigDecimal getTradingPnl() {
        return totalRealizedPnl;
    }

    public Map<String, BigDecimal> getAllMarketRewards() {
        return Map.copyOf(rewardByMarket);
    }
}
