package com.agentbot.engine;

import com.agentbot.engine.model.EngineFill;
import com.agentbot.engine.model.EngineOrder;
import com.agentbot.engine.model.SimulatedMarket;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

@Slf4j
@Component
public class PerformanceTracker {

    private final Map<String, MarketPerformance> marketPerf = new ConcurrentHashMap<>();

    public void recordFill(EngineFill fill, SimulatedMarket market) {
        MarketPerformance perf = marketPerf.computeIfAbsent(fill.getMarketId(), k -> new MarketPerformance());
        perf.totalFills.incrementAndGet();
        perf.totalVolume = perf.totalVolume.add(fill.getSize());
        perf.totalFees = perf.totalFees.add(fill.getFee());

        BigDecimal midAtFill = market.getMidPrice();
        BigDecimal slippage;
        if (fill.getSide() == EngineOrder.Side.BUY) {
            slippage = fill.getPrice().subtract(midAtFill);
        } else {
            slippage = midAtFill.subtract(fill.getPrice());
        }
        perf.totalSlippage = perf.totalSlippage.add(slippage.multiply(fill.getSize()));

        if (slippage.compareTo(BigDecimal.ZERO) > 0) {
            perf.adverseFills.incrementAndGet();
        }
    }

    public void recordPriceAfterFill(String marketId, BigDecimal priceMoveAfterFill) {
        MarketPerformance perf = marketPerf.get(marketId);
        if (perf == null) return;
        perf.totalAdverseMove = perf.totalAdverseMove.add(priceMoveAfterFill);
        perf.moveCount.incrementAndGet();
    }

    public double getAdverseSelectionRate(String marketId) {
        MarketPerformance perf = marketPerf.get(marketId);
        if (perf == null || perf.totalFills.get() == 0) return 0.0;
        return (double) perf.adverseFills.get() / perf.totalFills.get();
    }

    public BigDecimal getAvgSlippagePerUnit(String marketId) {
        MarketPerformance perf = marketPerf.get(marketId);
        if (perf == null || perf.totalVolume.compareTo(BigDecimal.ZERO) == 0) return BigDecimal.ZERO;
        return perf.totalSlippage.divide(perf.totalVolume, 6, RoundingMode.HALF_UP);
    }

    public double getFillRate(String marketId) {
        MarketPerformance perf = marketPerf.get(marketId);
        if (perf == null) return 0.0;
        return perf.totalFills.get();
    }

    public boolean isMarketProfitable(String marketId) {
        MarketPerformance perf = marketPerf.get(marketId);
        if (perf == null || perf.totalFills.get() < 5) return true;
        return perf.totalSlippage.compareTo(perf.totalFees.negate()) < 0;
    }

    public boolean shouldWidenSpread(String marketId) {
        return getAdverseSelectionRate(marketId) > 0.5;
    }

    public BigDecimal getSpreadAdjustment(String marketId) {
        double adverseRate = getAdverseSelectionRate(marketId);
        if (adverseRate > 0.6) return new BigDecimal("0.03");
        if (adverseRate > 0.4) return new BigDecimal("0.015");
        if (adverseRate < 0.2) return new BigDecimal("-0.005");
        return BigDecimal.ZERO;
    }

    public int getTotalFills(String marketId) {
        MarketPerformance perf = marketPerf.get(marketId);
        return perf != null ? perf.totalFills.get() : 0;
    }

    public Map<String, MarketPerformance> getAllPerformance() {
        return Map.copyOf(marketPerf);
    }

    public static class MarketPerformance {
        public final AtomicInteger totalFills = new AtomicInteger(0);
        public final AtomicInteger adverseFills = new AtomicInteger(0);
        public final AtomicLong moveCount = new AtomicLong(0);
        public BigDecimal totalVolume = BigDecimal.ZERO;
        public BigDecimal totalSlippage = BigDecimal.ZERO;
        public BigDecimal totalFees = BigDecimal.ZERO;
        public BigDecimal totalAdverseMove = BigDecimal.ZERO;
    }
}
