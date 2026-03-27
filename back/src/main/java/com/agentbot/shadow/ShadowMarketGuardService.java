package com.agentbot.shadow;

import com.agentbot.polymarket.ShadowConfig;
import lombok.Data;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class ShadowMarketGuardService {

    private final ShadowConfig cfg;

    @Getter
    private final Map<String, MarketGuardState> guardStates = new ConcurrentHashMap<>();

    private int sessionFillsTotal = 0;

    public void reset() {
        guardStates.clear();
        sessionFillsTotal = 0;
    }

    // ── Core API ──────────────────────────────────────────────────────────

    public boolean shouldQuote(String tokenId) {
        MarketGuardState g = guardStates.get(tokenId);
        if (g == null) return true;
        if (g.status == GuardStatus.DISABLED_SESSION) return false;
        if (g.status == GuardStatus.HARD_COOLDOWN) return false;
        if (g.status == GuardStatus.SOFT_COOLDOWN) return false;
        return true;
    }

    public double getGuardPenalty(String tokenId) {
        MarketGuardState g = guardStates.get(tokenId);
        if (g == null) return 0.0;
        return g.guardPenalty;
    }

    public GuardStatus getStatus(String tokenId) {
        MarketGuardState g = guardStates.get(tokenId);
        return g != null ? g.status : GuardStatus.ACTIVE;
    }

    // ── Event Recording ──────────────────────────────────────────────────

    public synchronized void recordFill(ShadowFill fill) {
        sessionFillsTotal++;
        MarketGuardState g = guardStates.computeIfAbsent(fill.getTokenId(), k -> new MarketGuardState(k));

        g.sessionFills++;
        g.fillTimestamps.add(fill.getFilledAt());
        g.fillPnls.add(fill.getEstimatedPnl());

        if ("BUY".equals(fill.getSide())) g.fillsBuy++;
        else g.fillsSell++;

        g.totalSlippage = g.totalSlippage.add(fill.getSlippage().abs());
        g.sessionPnl = g.sessionPnl.add(fill.getEstimatedPnl());

        if (fill.isWouldHaveBeenToxic()) g.sessionToxicFills++;

        if (fill.getEstimatedPnl().compareTo(BigDecimal.ZERO) < 0) {
            g.consecutiveNegativeFills++;
        } else {
            g.consecutiveNegativeFills = 0;
        }

        evaluateGuardRules(g);
    }

    public synchronized void recordCancel(String tokenId, String reason) {
        MarketGuardState g = guardStates.computeIfAbsent(tokenId, k -> new MarketGuardState(k));
        g.cancelCount++;
        if ("stale_timeout".equals(reason)) {
            g.staleCancelCount++;
        }
    }

    public synchronized void recordQuoteAttempt(String tokenId) {
        MarketGuardState g = guardStates.computeIfAbsent(tokenId, k -> new MarketGuardState(k));
        g.quoteAttempts++;
    }

    // ── Tick (called each cycle) ─────────────────────────────────────────

    public synchronized void tick(long currentCycle) {
        for (MarketGuardState g : guardStates.values()) {
            if (g.cooldownUntilCycle > 0 && currentCycle >= g.cooldownUntilCycle) {
                GuardStatus prev = g.status;
                g.status = GuardStatus.ACTIVE;
                g.cooldownUntilCycle = 0;
                g.guardPenalty = 0.0;
                if (prev != GuardStatus.ACTIVE) {
                    log.info("[MARKET_GUARD_STATE]\nmarket={}\nprev_state={}\nnew_state=ACTIVE\nreason=cooldown_expired" +
                                    "\nrolling_pnl_5m={}\nfills_share={}\nstale_cancel_rate={}\navg_slippage={}\nconsecutive_negative_fills={}",
                            shortId(g.tokenId), prev,
                            fmt(getRollingPnl5m(g)), fmtPct(getFillsShare(g)),
                            fmtPct(getStaleCancelRate(g)), fmt(getAvgSlippage(g)),
                            g.consecutiveNegativeFills);
                }
            }
        }
    }

    // ── Guard Rule Engine ────────────────────────────────────────────────

    private void evaluateGuardRules(MarketGuardState g) {
        GuardStatus prevStatus = g.status;

        BigDecimal rollingPnl5m = getRollingPnl5m(g);
        double fillsShare = getFillsShare(g);
        double staleCancelRate = getStaleCancelRate(g);
        BigDecimal avgSlippage = getAvgSlippage(g);
        BigDecimal rollingPnl1m = getRollingPnl1m(g);
        double fillRate = g.quoteAttempts > 0 ? (double) g.sessionFills / g.quoteAttempts : 0.0;

        double pnl5mThreshold = cfg.getGuardPnl5mThreshold();
        double fillsShareThreshold = cfg.getGuardFillsShareThreshold();
        int maxConsecNeg = cfg.getGuardMaxConsecutiveNegative();
        double staleCancelThreshold = cfg.getGuardStaleCancelRateThreshold();
        double slippageThreshold = cfg.getGuardAvgSlippageThreshold();

        // Rule 1: rolling PnL 5m too negative => SOFT_COOLDOWN
        if (rollingPnl5m.doubleValue() < pnl5mThreshold && g.status == GuardStatus.ACTIVE) {
            transitionState(g, GuardStatus.SOFT_COOLDOWN, "rolling_pnl_5m_negative",
                    cfg.getGuardSoftCooldownCycles());
        }

        // Rule 2: high fills share + negative PnL => HARD_COOLDOWN
        if (fillsShare > fillsShareThreshold && g.sessionPnl.doubleValue() < 0 && g.status.ordinal() < GuardStatus.HARD_COOLDOWN.ordinal()) {
            transitionState(g, GuardStatus.HARD_COOLDOWN, "concentration_with_loss",
                    cfg.getGuardHardCooldownCycles());
        }

        // Rule 3: too many consecutive negative fills => HARD_COOLDOWN
        if (g.consecutiveNegativeFills >= maxConsecNeg && g.status.ordinal() < GuardStatus.HARD_COOLDOWN.ordinal()) {
            transitionState(g, GuardStatus.HARD_COOLDOWN, "consecutive_negative_fills",
                    cfg.getGuardHardCooldownCycles());
        }

        // Rule 4: high stale cancel rate + low fill rate => edge penalty
        if (staleCancelRate > staleCancelThreshold && fillRate < 0.05 && g.status == GuardStatus.ACTIVE) {
            g.guardPenalty = Math.min(g.guardPenalty + 0.3, 0.9);
            log.info("[MARKET_GUARD_PENALTY]\nmarket={}\nraw_edge=n/a\nguard_penalty={}\nfinal_edge=n/a\nreason=high_stale_cancel_rate",
                    shortId(g.tokenId), String.format("%.4f", g.guardPenalty));
        }

        // Rule 5: high average slippage => edge penalty
        if (avgSlippage.doubleValue() > slippageThreshold && g.sessionFills >= 5) {
            g.guardPenalty = Math.min(g.guardPenalty + 0.2, 0.9);
            log.info("[MARKET_GUARD_PENALTY]\nmarket={}\nraw_edge=n/a\nguard_penalty={}\nfinal_edge=n/a\nreason=high_avg_slippage",
                    shortId(g.tokenId), String.format("%.4f", g.guardPenalty));
        }

        // Rule 6: one-sided flow => penalty
        if (g.sessionFills >= 10) {
            double sideRatio = Math.max(g.fillsBuy, g.fillsSell) / (double) g.sessionFills;
            if (sideRatio > 0.8) {
                g.guardPenalty = Math.min(g.guardPenalty + 0.15, 0.9);
                log.info("[MARKET_GUARD_PENALTY]\nmarket={}\nraw_edge=n/a\nguard_penalty={}\nfinal_edge=n/a\nreason=one_sided_flow",
                        shortId(g.tokenId), String.format("%.4f", g.guardPenalty));
            }
        }

        // Rule 7: severe session loss => DISABLED_SESSION
        if (g.sessionPnl.doubleValue() < pnl5mThreshold * 5 && g.sessionFills >= 10) {
            if (g.status != GuardStatus.DISABLED_SESSION) {
                transitionState(g, GuardStatus.DISABLED_SESSION, "severe_session_loss", 0);
            }
        }

        if (prevStatus != g.status) {
            log.info("[MARKET_GUARD_STATE]\nmarket={}\nprev_state={}\nnew_state={}\nreason={}" +
                            "\nrolling_pnl_1m={}\nrolling_pnl_5m={}\nfills_share={}\nstale_cancel_rate={}" +
                            "\navg_slippage={}\nconsecutive_negative_fills={}",
                    shortId(g.tokenId), prevStatus, g.status, g.lastTransitionReason,
                    fmt(rollingPnl1m), fmt(rollingPnl5m), fmtPct(fillsShare),
                    fmtPct(staleCancelRate), fmt(avgSlippage), g.consecutiveNegativeFills);
        }
    }

    private void transitionState(MarketGuardState g, GuardStatus newStatus, String reason, int cooldownCycles) {
        g.status = newStatus;
        g.lastTransitionReason = reason;
        g.transitionCount++;
        if (cooldownCycles > 0) {
            g.cooldownUntilCycle = getCurrentApproxCycle() + cooldownCycles;
        }
    }

    // actual cycle is set externally via tick(); this is a fallback
    private long currentCycleApprox = 0;
    private long getCurrentApproxCycle() { return currentCycleApprox; }
    public void setCycleCount(long cycle) { this.currentCycleApprox = cycle; }

    // ── Rolling Metric Calculators ───────────────────────────────────────

    private BigDecimal getRollingPnl(MarketGuardState g, Duration window) {
        Instant cutoff = Instant.now().minus(window);
        BigDecimal sum = BigDecimal.ZERO;
        int idx = g.fillTimestamps.size() - 1;
        for (int i = idx; i >= 0; i--) {
            if (g.fillTimestamps.get(i).isBefore(cutoff)) break;
            sum = sum.add(g.fillPnls.get(i));
        }
        return sum;
    }

    public BigDecimal getRollingPnl5m(MarketGuardState g) {
        return getRollingPnl(g, Duration.ofMinutes(5));
    }

    public BigDecimal getRollingPnl1m(MarketGuardState g) {
        return getRollingPnl(g, Duration.ofMinutes(1));
    }

    public double getFillsShare(MarketGuardState g) {
        if (sessionFillsTotal <= 0) return 0.0;
        return (double) g.sessionFills / sessionFillsTotal;
    }

    public double getStaleCancelRate(MarketGuardState g) {
        int total = g.cancelCount + g.sessionFills;
        if (total <= 0) return 0.0;
        return (double) g.staleCancelCount / total;
    }

    public BigDecimal getAvgSlippage(MarketGuardState g) {
        if (g.sessionFills <= 0) return BigDecimal.ZERO;
        return g.totalSlippage.divide(BigDecimal.valueOf(g.sessionFills), 6, RoundingMode.HALF_UP);
    }

    public double getFillRate(MarketGuardState g) {
        if (g.quoteAttempts <= 0) return 0.0;
        return (double) g.sessionFills / g.quoteAttempts;
    }

    // ── Snapshot Logging ─────────────────────────────────────────────────

    public void logQualitySnapshots() {
        for (MarketGuardState g : guardStates.values()) {
            if (g.sessionFills == 0 && g.quoteAttempts == 0) continue;
            log.info("[MARKET_QUALITY_SNAPSHOT]\nmarket={}\nstatus={}\nfill_rate_5m={}\nstale_cancel_rate_5m={}" +
                            "\ntoxic_rate_5m={}\nslippage_avg_5m={}\npnl_5m={}\nquote_count={}\nfill_count={}\nguard_penalty={}",
                    shortId(g.tokenId), g.status,
                    fmtPct(getFillRate(g)),
                    fmtPct(getStaleCancelRate(g)),
                    fmtPct(g.sessionFills > 0 ? (double) g.sessionToxicFills / g.sessionFills : 0.0),
                    fmt(getAvgSlippage(g)),
                    fmt(getRollingPnl5m(g)),
                    g.quoteAttempts, g.sessionFills,
                    String.format("%.4f", g.guardPenalty));
        }
    }

    // ── Session Summary ──────────────────────────────────────────────────

    public Map<String, Object> getGuardSummary() {
        Map<String, Object> summary = new LinkedHashMap<>();

        long softCount = guardStates.values().stream().filter(g -> g.status == GuardStatus.SOFT_COOLDOWN).count();
        long hardCount = guardStates.values().stream().filter(g -> g.status == GuardStatus.HARD_COOLDOWN).count();
        long disabledCount = guardStates.values().stream().filter(g -> g.status == GuardStatus.DISABLED_SESSION).count();
        long totalTransitions = guardStates.values().stream().mapToLong(g -> g.transitionCount).sum();

        summary.put("marketsTracked", guardStates.size());
        summary.put("softCooldownCount", softCount);
        summary.put("hardCooldownCount", hardCount);
        summary.put("disabledSessionCount", disabledCount);
        summary.put("totalTransitions", totalTransitions);

        // Concentration: HHI
        if (sessionFillsTotal > 0) {
            double hhi = guardStates.values().stream()
                    .mapToDouble(g -> Math.pow((double) g.sessionFills / sessionFillsTotal, 2))
                    .sum();
            summary.put("hhi", BigDecimal.valueOf(hhi).setScale(4, RoundingMode.HALF_UP));

            Optional<MarketGuardState> topConcentration = guardStates.values().stream()
                    .max(Comparator.comparingInt(g -> g.sessionFills));
            topConcentration.ifPresent(g -> {
                summary.put("topFillsMarket", shortId(g.tokenId));
                summary.put("topFillsShare", BigDecimal.valueOf(getFillsShare(g)).setScale(4, RoundingMode.HALF_UP));
                summary.put("topFillsPnl", g.sessionPnl.setScale(4, RoundingMode.HALF_UP));
            });
        }

        // PnL saved by guard
        BigDecimal pnlSaved = BigDecimal.ZERO;
        for (MarketGuardState g : guardStates.values()) {
            if (g.status != GuardStatus.ACTIVE && g.sessionPnl.compareTo(BigDecimal.ZERO) < 0) {
                pnlSaved = pnlSaved.add(g.sessionPnl.abs().multiply(BigDecimal.valueOf(0.5)));
            }
        }
        summary.put("estimatedPnlSaved", pnlSaved.setScale(4, RoundingMode.HALF_UP));

        return summary;
    }

    public List<Map<String, Object>> getPerMarketGuardData() {
        return guardStates.values().stream()
                .sorted(Comparator.comparing((MarketGuardState g) -> g.sessionPnl))
                .map(g -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("tokenId", g.tokenId);
                    m.put("status", g.status.name());
                    m.put("sessionFills", g.sessionFills);
                    m.put("sessionPnl", g.sessionPnl.setScale(4, RoundingMode.HALF_UP));
                    m.put("fillsShare", BigDecimal.valueOf(getFillsShare(g)).setScale(4, RoundingMode.HALF_UP));
                    m.put("rollingPnl5m", getRollingPnl5m(g).setScale(4, RoundingMode.HALF_UP));
                    m.put("avgSlippage", getAvgSlippage(g).setScale(6, RoundingMode.HALF_UP));
                    m.put("staleCancelRate", BigDecimal.valueOf(getStaleCancelRate(g)).setScale(4, RoundingMode.HALF_UP));
                    m.put("fillRate", BigDecimal.valueOf(getFillRate(g)).setScale(4, RoundingMode.HALF_UP));
                    m.put("consecutiveNegFills", g.consecutiveNegativeFills);
                    m.put("guardPenalty", BigDecimal.valueOf(g.guardPenalty).setScale(4, RoundingMode.HALF_UP));
                    m.put("fillsBuy", g.fillsBuy);
                    m.put("fillsSell", g.fillsSell);
                    m.put("cancelCount", g.cancelCount);
                    m.put("staleCancelCount", g.staleCancelCount);
                    m.put("quoteAttempts", g.quoteAttempts);
                    m.put("sessionToxicFills", g.sessionToxicFills);
                    m.put("transitionCount", g.transitionCount);
                    m.put("lastReason", g.lastTransitionReason);
                    return m;
                }).collect(Collectors.toList());
    }

    // ── Types ────────────────────────────────────────────────────────────

    public enum GuardStatus {
        ACTIVE,
        SOFT_COOLDOWN,
        HARD_COOLDOWN,
        DISABLED_SESSION
    }

    @Data
    public static class MarketGuardState {
        final String tokenId;
        GuardStatus status = GuardStatus.ACTIVE;
        double guardPenalty = 0.0;
        long cooldownUntilCycle = 0;

        int sessionFills = 0;
        int fillsBuy = 0;
        int fillsSell = 0;
        int sessionToxicFills = 0;
        BigDecimal sessionPnl = BigDecimal.ZERO;
        BigDecimal totalSlippage = BigDecimal.ZERO;
        int cancelCount = 0;
        int staleCancelCount = 0;
        int quoteAttempts = 0;
        int consecutiveNegativeFills = 0;
        int transitionCount = 0;
        String lastTransitionReason = "none";

        List<Instant> fillTimestamps = new ArrayList<>();
        List<BigDecimal> fillPnls = new ArrayList<>();

        MarketGuardState(String tokenId) {
            this.tokenId = tokenId;
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    private static String shortId(String id) {
        return id != null && id.length() > 16 ? id.substring(0, 16) : (id != null ? id : "null");
    }

    private static String fmt(BigDecimal v) {
        return v != null ? v.setScale(4, RoundingMode.HALF_UP).toPlainString() : "0.0000";
    }

    private static String fmtPct(double v) {
        return String.format("%.2f%%", v * 100);
    }
}
