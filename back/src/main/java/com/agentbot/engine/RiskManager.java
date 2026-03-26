package com.agentbot.engine;

import com.agentbot.engine.model.InventoryPosition;
import com.agentbot.engine.model.RiskLimits;
import com.agentbot.engine.model.SimulatedMarket;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

@Slf4j
@Component
@RequiredArgsConstructor
public class RiskManager {

    @Getter
    private final RiskLimits limits = RiskLimits.builder().build();

    private final PerformanceTracker performanceTracker;

    @Getter
    private boolean globalTradingAllowed = true;

    @Getter
    private String pauseReason = "";

    public boolean canTrade(SimulatedMarket market, InventoryPosition position,
                            int activeOrdersForMarket, int totalActiveOrders) {
        if (!globalTradingAllowed) return false;

        if (market.getVolatility().compareTo(limits.getMaxVolatilityThreshold()) > 0) {
            log.debug("Risk: vol too high for {}", market.getMarketId());
            return false;
        }

        if (market.getSpread().compareTo(limits.getMinSpreadToQuote()) < 0) {
            log.debug("Risk: spread too tight for {} ({})", market.getMarketId(), market.getSpread());
            return false;
        }

        if (market.getLiquidityScore().compareTo(limits.getMinLiquidityScore()) < 0) {
            return false;
        }

        if (market.getRewardScore().compareTo(limits.getMinRewardScore()) < 0) {
            return false;
        }

        if (position.getTotalExposure().compareTo(limits.getMaxPositionPerMarket()) > 0) {
            log.debug("Risk: position limit for {}", market.getMarketId());
            return false;
        }

        if (activeOrdersForMarket >= limits.getMaxActiveOrdersPerMarket()) {
            return false;
        }

        if (totalActiveOrders >= limits.getMaxActiveOrdersGlobal()) {
            return false;
        }

        if (!performanceTracker.isMarketProfitable(market.getMarketId())
                && performanceTracker.getTotalFills(market.getMarketId()) > 10) {
            log.debug("Risk: market {} unprofitable, reducing activity", market.getMarketId());
            return false;
        }

        return true;
    }

    public void evaluateGlobalRisk(BigDecimal globalExposure) {
        if (globalExposure.compareTo(limits.getMaxGlobalExposure()) > 0) {
            globalTradingAllowed = false;
            pauseReason = "Global exposure: " + globalExposure;
            log.warn("RISK: Paused - {}", pauseReason);
        } else if (!globalTradingAllowed
                && globalExposure.compareTo(limits.getMaxGlobalExposure().multiply(new BigDecimal("0.6"))) < 0) {
            globalTradingAllowed = true;
            pauseReason = "";
            log.info("RISK: Resumed");
        }
    }

    public int getMaxOrdersPerSide() {
        return limits.getMaxActiveOrdersPerMarket() / 2;
    }
}
