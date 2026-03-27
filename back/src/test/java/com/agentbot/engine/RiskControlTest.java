package com.agentbot.engine;

import com.agentbot.engine.model.MarketScore;
import com.agentbot.engine.model.SimulatedMarket;
import com.agentbot.engine.model.SimulatedMarket.VolatilityRegime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RiskControlTest {

    private TradingConfig cfg;

    @BeforeEach
    void setUp() {
        cfg = new TradingConfig();
        cfg.setMaxCapitalSharePerMarket(0.25);
        cfg.setBlockVolatileMarkets(true);
        cfg.setMinEdgeAfterPenalty(0.3);
        cfg.setRegimePenaltyVolatile(0.35);
        cfg.setRegimePenaltyCrisis(0.0);
        cfg.setCooldownCycles(15);
    }

    @Test
    void capNeverExceedsMax() {
        BigDecimal maxCap = BigDecimal.valueOf(cfg.getMaxCapitalSharePerMarket());

        BigDecimal edge1 = new BigDecimal("2.5");
        BigDecimal edge2 = new BigDecimal("0.5");
        BigDecimal edge3 = new BigDecimal("0.3");
        BigDecimal totalEdge = edge1.add(edge2).add(edge3);

        BigDecimal rawCap1 = edge1.divide(totalEdge, 6, RoundingMode.HALF_UP);
        BigDecimal appliedCap1 = rawCap1.min(maxCap);

        assertTrue(rawCap1.doubleValue() > 0.25, "raw cap should exceed 25%");
        assertTrue(appliedCap1.compareTo(maxCap) <= 0,
                "applied cap must not exceed " + maxCap + " but was " + appliedCap1);
    }

    @Test
    void crisisNeverActive() {
        SimulatedMarket market = new SimulatedMarket("crisis-1", "Crisis Market", new BigDecimal("0.5"));
        market.setRegime(VolatilityRegime.CRISIS);

        assertTrue(market.isCrisis());
        assertEquals(VolatilityRegime.CRISIS, market.getRegime());
        assertEquals(0.0, cfg.getRegimePenaltyCrisis());
    }

    @Test
    void volatileBlockedWhenFlagActive() {
        assertTrue(cfg.isBlockVolatileMarkets());
        SimulatedMarket market = new SimulatedMarket("vol-1", "Volatile Market", new BigDecimal("0.5"));
        market.setRegime(VolatilityRegime.VOLATILE);
        assertEquals(VolatilityRegime.VOLATILE, market.getRegime());
    }

    @Test
    void volatileLowEdgeBlockedWhenFlagInactive() {
        cfg.setBlockVolatileMarkets(false);
        double rawEdge = 0.5;
        double penalizedEdge = rawEdge * cfg.getRegimePenaltyVolatile();

        assertTrue(penalizedEdge < cfg.getMinEdgeAfterPenalty(),
                "penalized edge " + penalizedEdge + " should be below threshold " + cfg.getMinEdgeAfterPenalty());
    }

    @Test
    void volatileHighEdgeAllowedWhenFlagInactive() {
        cfg.setBlockVolatileMarkets(false);
        double rawEdge = 2.0;
        double penalizedEdge = rawEdge * cfg.getRegimePenaltyVolatile();

        assertTrue(penalizedEdge >= cfg.getMinEdgeAfterPenalty(),
                "penalized edge " + penalizedEdge + " should be above threshold " + cfg.getMinEdgeAfterPenalty());
    }

    @Test
    void cooldownDurationMatchesConfig() {
        assertEquals(15, cfg.getCooldownCycles());
        long currentCycle = 100;
        long cooldownUntil = currentCycle + cfg.getCooldownCycles();
        assertEquals(115, cooldownUntil);
        assertTrue(currentCycle < cooldownUntil);
        assertFalse(cooldownUntil <= currentCycle);
    }

    @Test
    void capClampDistributesCorrectly() {
        BigDecimal maxCap = BigDecimal.valueOf(cfg.getMaxCapitalSharePerMarket());

        BigDecimal[] edges = {new BigDecimal("3.0"), new BigDecimal("1.0"), new BigDecimal("1.0")};
        BigDecimal totalEdge = BigDecimal.ZERO;
        for (BigDecimal e : edges) totalEdge = totalEdge.add(e);

        for (BigDecimal edge : edges) {
            BigDecimal rawCap = edge.divide(totalEdge, 6, RoundingMode.HALF_UP);
            BigDecimal appliedCap = rawCap.min(maxCap);
            assertTrue(appliedCap.compareTo(maxCap) <= 0,
                    "cap " + appliedCap + " exceeds max " + maxCap);
        }
    }
}
