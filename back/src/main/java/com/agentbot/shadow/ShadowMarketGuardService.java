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

/**
 * Per-market quality guard: rolling metrics, classification, proportional penalties, cooldowns.
 * Winners are capped so profitable markets are not pushed out by {@code edge_below_min_after_guard}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ShadowMarketGuardService {

    private static final Duration WINDOW_5M = Duration.ofMinutes(5);
    private static final Duration WINDOW_1M = Duration.ofMinutes(1);
    private static final Duration PRUNE_OLDER_THAN = Duration.ofMinutes(6);

    private final ShadowConfig cfg;

    @Getter
    private final Map<String, MarketGuardState> guardStates = new ConcurrentHashMap<>();

    private final List<Instant> globalFillInstants = Collections.synchronizedList(new ArrayList<>());

    private int sessionFillsTotal = 0;
    private long currentCycleApprox = 0;

    public void reset() {
        guardStates.clear();
        globalFillInstants.clear();
        sessionFillsTotal = 0;
    }

    public void setCycleCount(long cycle) {
        this.currentCycleApprox = cycle;
    }

    // ── Core API (engine) ─────────────────────────────────────────────────

    public boolean shouldQuote(String tokenId) {
        MarketGuardState g = guardStates.get(tokenId);
        if (g == null) return true;
        if (g.status == GuardStatus.DISABLED_SESSION) return false;
        if (g.status == GuardStatus.HARD_COOLDOWN) return false;
        if (g.status == GuardStatus.SOFT_COOLDOWN) return false;
        return true;
    }

    /** Cached penalty from last {@link #beforeQuoteCycle(Set)}. */
    public double getGuardPenalty(String tokenId) {
        MarketGuardState g = guardStates.get(tokenId);
        if (g == null) return 0.0;
        return g.cachedFinalPenalty;
    }

    public GuardStatus getStatus(String tokenId) {
        MarketGuardState g = guardStates.get(tokenId);
        return g != null ? g.status : GuardStatus.ACTIVE;
    }

    public MarketGuardClassification getClassification(String tokenId) {
        MarketGuardState g = guardStates.get(tokenId);
        return g != null ? g.cachedClassification : MarketGuardClassification.NEUTRAL;
    }

    /**
     * Call once per shadow cycle before evaluating edges: prune, tick cooldowns, recompute penalty cache.
     */
    public synchronized void beforeQuoteCycle(Set<String> liveTokenIds, long cycle) {
        setCycleCount(cycle);
        tick(cycle);
        pruneGlobalFills();
        for (MarketGuardState g : guardStates.values()) {
            if (!liveTokenIds.contains(g.tokenId) && g.sessionFills == 0 && g.quoteAttempts == 0) {
                continue;
            }
            pruneMarketEvents(g);
            RollingSnapshot snap = buildRollingSnapshot(g);
            MarketGuardClassification cls = classifyMarket(g, snap);
            PenaltyBreakdown pb = computePenaltyBreakdown(g, snap, cls);
            applyWinnerProtection(pb, snap, cls);

            g.cachedClassification = cls;
            g.cachedFinalPenalty = pb.finalPenalty;
            g.cachedBase = pb.base;
            g.cachedLoss = pb.loss;
            g.cachedToxicity = pb.toxicity;
            g.cachedChurn = pb.churn;
            g.cachedConcentration = pb.concentration;
            g.lastRollingSnapshot = snap;

            if (cls != g.lastLoggedClassification || Math.abs(pb.finalPenalty - g.lastLoggedPenalty) > 0.02) {
                logClassification(g, snap, cls);
                g.lastLoggedClassification = cls;
                g.lastLoggedPenalty = pb.finalPenalty;
            }
        }
    }

    /**
     * Full penalty line with edges; call from engine after applying guard.
     * INFO if edge killed by guard, high penalty, or bad classification; else DEBUG to limit volume on winners.
     */
    public void logMarketGuardPenaltyWithEdges(String tokenId, BigDecimal rawEdge, BigDecimal finalEdge,
                                               boolean edgeBelowMinAfterGuard) {
        MarketGuardState g = guardStates.get(tokenId);
        if (g == null || g.cachedFinalPenalty <= 0.0) return;
        boolean loud = edgeBelowMinAfterGuard
                || g.cachedFinalPenalty >= 0.05
                || g.cachedClassification == MarketGuardClassification.LOSER
                || g.cachedClassification == MarketGuardClassification.TOXIC
                || g.cachedClassification == MarketGuardClassification.HIGH_CHURN;
        String msg = "[MARKET_GUARD_PENALTY]\nmarket={}\nclassification={}\nbase_penalty={}\nloss_penalty={}" +
                "\ntoxicity_penalty={}\nchurn_penalty={}\nconcentration_penalty={}\nfinal_penalty={}" +
                "\nraw_edge={}\nfinal_edge={}\ncooldown_state={}";
        if (loud) {
            log.info(msg, shortId(tokenId), g.cachedClassification.name(),
                    String.format("%.4f", g.cachedBase),
                    String.format("%.4f", g.cachedLoss),
                    String.format("%.4f", g.cachedToxicity),
                    String.format("%.4f", g.cachedChurn),
                    String.format("%.4f", g.cachedConcentration),
                    String.format("%.4f", g.cachedFinalPenalty),
                    fmt(rawEdge), fmt(finalEdge), g.status.name());
        } else {
            log.debug(msg, shortId(tokenId), g.cachedClassification.name(),
                    String.format("%.4f", g.cachedBase),
                    String.format("%.4f", g.cachedLoss),
                    String.format("%.4f", g.cachedToxicity),
                    String.format("%.4f", g.cachedChurn),
                    String.format("%.4f", g.cachedConcentration),
                    String.format("%.4f", g.cachedFinalPenalty),
                    fmt(rawEdge), fmt(finalEdge), g.status.name());
        }
    }

    // ── Events ───────────────────────────────────────────────────────────

    public synchronized void recordFill(ShadowFill fill) {
        sessionFillsTotal++;
        Instant now = fill.getFilledAt() != null ? fill.getFilledAt() : Instant.now();
        globalFillInstants.add(now);

        MarketGuardState g = guardStates.computeIfAbsent(fill.getTokenId(), MarketGuardState::new);
        g.sessionFills++;
        if ("BUY".equals(fill.getSide())) g.fillsBuy++;
        else g.fillsSell++;

        g.totalSlippage = g.totalSlippage.add(fill.getSlippage().abs());
        g.sessionPnl = g.sessionPnl.add(fill.getEstimatedPnl());
        if (fill.isWouldHaveBeenToxic()) g.sessionToxicFills++;

        g.fillEvents.add(new FillEvent(now, fill.getEstimatedPnl(), fill.isWouldHaveBeenToxic(),
                fill.getSlippage().abs()));

        if (fill.getEstimatedPnl().compareTo(BigDecimal.ZERO) < 0) {
            g.consecutiveNegativeFills++;
        } else {
            g.consecutiveNegativeFills = 0;
        }

        pruneMarketEvents(g);
        RollingSnapshot snap = buildRollingSnapshot(g);
        MarketGuardClassification cls = classifyMarket(g, snap);
        g.cachedClassification = cls;
        evaluateStrongCooldownOnly(g, snap, cls);
    }

    public synchronized void recordCancel(String tokenId, String reason) {
        MarketGuardState g = guardStates.computeIfAbsent(tokenId, MarketGuardState::new);
        g.cancelCount++;
        if ("stale_timeout".equals(reason)) {
            g.staleCancelCount++;
            g.staleCancelInstants.add(Instant.now());
        }
    }

    public synchronized void recordQuoteAttempt(String tokenId) {
        guardStates.computeIfAbsent(tokenId, MarketGuardState::new).quoteAttempts++;
    }

    // ── Tick / cooldown expiry ────────────────────────────────────────────

    private void tick(long currentCycle) {
        for (MarketGuardState g : guardStates.values()) {
            if (g.cooldownUntilCycle > 0 && currentCycle >= g.cooldownUntilCycle) {
                GuardStatus prev = g.status;
                g.status = GuardStatus.ACTIVE;
                g.cooldownUntilCycle = 0;
                g.cachedFinalPenalty = 0.0;
                if (prev != GuardStatus.ACTIVE) {
                    RollingSnapshot snap = buildRollingSnapshot(g);
                    log.info("[MARKET_GUARD_DECISION]\nmarket={}\naction=ALLOW\nreason=cooldown_expired\nclassification={}\nfinal_penalty=0.0000\ndisable_until=0",
                            shortId(g.tokenId), g.cachedClassification);
                    log.info("[MARKET_GUARD_STATE]\nmarket={}\nprev_state={}\nnew_state=ACTIVE\nreason=cooldown_expired" +
                                    "\nrolling_pnl_5m={}\nfills_share={}\nstale_cancel_rate={}\navg_slippage={}\nconsecutive_negative_fills={}",
                            shortId(g.tokenId), prev, fmt(snap.pnl5m), fmtPct(snap.fillShareSession),
                            fmtPct(snap.staleCancelRateSession), fmt(snap.avgSlippageSession), g.consecutiveNegativeFills);
                }
            }
        }
    }

    private void pruneGlobalFills() {
        Instant cutoff = Instant.now().minus(PRUNE_OLDER_THAN);
        globalFillInstants.removeIf(t -> t.isBefore(cutoff));
    }

    private void pruneMarketEvents(MarketGuardState g) {
        Instant cutoff = Instant.now().minus(PRUNE_OLDER_THAN);
        g.fillEvents.removeIf(e -> e.instant.isBefore(cutoff));
        g.staleCancelInstants.removeIf(t -> t.isBefore(cutoff));
    }

    // ── Rolling metrics ───────────────────────────────────────────────────

    private RollingSnapshot buildRollingSnapshot(MarketGuardState g) {
        Instant t5 = Instant.now().minus(WINDOW_5M);
        Instant t1 = Instant.now().minus(WINDOW_1M);

        int fills5m = 0;
        int toxic5m = 0;
        BigDecimal pnl5m = BigDecimal.ZERO;
        BigDecimal slipSum5m = BigDecimal.ZERO;

        int fills1m = 0;
        BigDecimal pnl1m = BigDecimal.ZERO;

        for (FillEvent e : g.fillEvents) {
            if (!e.instant.isBefore(t5)) {
                fills5m++;
                pnl5m = pnl5m.add(e.pnl);
                slipSum5m = slipSum5m.add(e.slippageAbs);
                if (e.toxic) toxic5m++;
            }
            if (!e.instant.isBefore(t1)) {
                fills1m++;
                pnl1m = pnl1m.add(e.pnl);
            }
        }

        double toxicRate5m = fills5m > 0 ? (double) toxic5m / fills5m : 0.0;
        BigDecimal avgSlip5m = fills5m > 0
                ? slipSum5m.divide(BigDecimal.valueOf(fills5m), 6, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;

        int stale5m = (int) g.staleCancelInstants.stream().filter(t -> !t.isBefore(t5)).count();
        int cancels5m = stale5m;
        double staleRate5m = (fills5m + cancels5m) > 0 ? (double) stale5m / (fills5m + cancels5m) : 0.0;

        int globalFills5m = (int) globalFillInstants.stream().filter(t -> !t.isBefore(t5)).count();
        double fillShare5m = globalFills5m > 0 ? (double) fills5m / globalFills5m : 0.0;

        double fillShareSession = sessionFillsTotal > 0 ? (double) g.sessionFills / sessionFillsTotal : 0.0;
        int totalSession = g.cancelCount + g.sessionFills;
        double staleCancelRateSession = totalSession > 0 ? (double) g.staleCancelCount / totalSession : 0.0;
        BigDecimal avgSlippageSession = g.sessionFills > 0
                ? g.totalSlippage.divide(BigDecimal.valueOf(g.sessionFills), 6, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;

        double fillRate = g.quoteAttempts > 0 ? (double) g.sessionFills / g.quoteAttempts : 0.0;

        return new RollingSnapshot(pnl1m, pnl5m, fills5m, toxicRate5m, staleRate5m, avgSlip5m,
                fillShare5m, fillShareSession, staleCancelRateSession, avgSlippageSession, fillRate, fills1m, toxic5m, stale5m);
    }

    // ── Classification ────────────────────────────────────────────────────

    private MarketGuardClassification classifyMarket(MarketGuardState g, RollingSnapshot s) {
        double toxicHard = cfg.getGuardToxicRateClassification();
        int minFillsClass = cfg.getGuardMinFillsForClassification();

        if (s.fills5m >= minFillsClass && s.toxicRate5m >= toxicHard) {
            return MarketGuardClassification.TOXIC;
        }

        if (s.staleCancelRate5m >= cfg.getGuardChurnStaleRate5m()
                && s.staleEvents5m >= cfg.getGuardChurnMinStaleEvents5m()
                && s.fills5m <= cfg.getGuardChurnMaxFills5m()) {
            return MarketGuardClassification.HIGH_CHURN;
        }

        if (g.consecutiveNegativeFills >= 4 && s.pnl5m.doubleValue() < -0.01 && s.fills5m >= 2) {
            return MarketGuardClassification.LOSER;
        }

        if (s.pnl5m.doubleValue() < cfg.getGuardLoserPnl5mThreshold() && s.fills5m >= 3) {
            return MarketGuardClassification.LOSER;
        }

        if (s.pnl5m.doubleValue() > 0
                && s.toxicRate5m == 0.0
                && s.fills5m >= cfg.getGuardWinnerMinFills5m()) {
            return MarketGuardClassification.WINNER;
        }

        return MarketGuardClassification.NEUTRAL;
    }

    // ── Penalty (compound) ────────────────────────────────────────────────

    private PenaltyBreakdown computePenaltyBreakdown(MarketGuardState g, RollingSnapshot s,
                                                     MarketGuardClassification cls) {
        PenaltyBreakdown pb = new PenaltyBreakdown();

        pb.base = switch (cls) {
            case WINNER -> cfg.getGuardPenaltyBaseWinner();
            case NEUTRAL -> cfg.getGuardPenaltyBaseNeutral();
            case LOSER -> cfg.getGuardPenaltyBaseLoser();
            case TOXIC -> cfg.getGuardPenaltyBaseToxic();
            case HIGH_CHURN -> cfg.getGuardPenaltyBaseHighChurn();
        };

        double lossMag = Math.max(0.0, -s.pnl5m.doubleValue());
        pb.loss = cls == MarketGuardClassification.WINNER ? 0.0
                : Math.min(0.35, lossMag * cfg.getGuardLossPenaltyK());

        pb.toxicity = cls == MarketGuardClassification.TOXIC
                ? cfg.getGuardToxicityPenaltyExtra()
                : s.toxicRate5m * cfg.getGuardToxicityPenaltyK();

        pb.churn = s.staleCancelRate5m * cfg.getGuardChurnPenaltyK();

        double conc = Math.max(0.0, s.fillShareSession - cfg.getGuardConcentrationShareSoft());
        pb.concentration = g.sessionPnl.doubleValue() < 0
                ? conc * cfg.getGuardConcentrationPenaltyKLoss()
                : conc * cfg.getGuardConcentrationPenaltyKWinner();

        double raw = pb.base + pb.loss + pb.toxicity + pb.churn + pb.concentration;
        pb.finalPenalty = Math.min(0.95, Math.max(0.0, raw));
        return pb;
    }

    /** Cap penalty for clear winners unless toxicity/churn is extreme. */
    private void applyWinnerProtection(PenaltyBreakdown pb, RollingSnapshot s, MarketGuardClassification cls) {
        if (cls != MarketGuardClassification.WINNER) {
            return;
        }
        if (s.toxicRate5m > 0.001) {
            return;
        }
        if (s.staleCancelRate5m >= cfg.getGuardWinnerChurnOverrideStale()) {
            return;
        }
        double cap = cfg.getGuardMaxPenaltyWinner();
        if (pb.finalPenalty > cap) {
            pb.finalPenalty = cap;
        }
    }

    private void logClassification(MarketGuardState g, RollingSnapshot s, MarketGuardClassification cls) {
        log.info("[MARKET_GUARD_CLASSIFICATION]\nmarket={}\nclassification={}\npnl_1m={}\npnl_5m={}\nfills_5m={}" +
                        "\ntoxic_rate_5m={}\nstale_cancel_rate_5m={}\navg_slippage_5m={}\nconsecutive_negative_fills={}\nfill_share_5m={}",
                shortId(g.tokenId), cls.name(),
                fmt(s.pnl1m), fmt(s.pnl5m), s.fills5m,
                String.format("%.4f", s.toxicRate5m),
                String.format("%.4f", s.staleCancelRate5m),
                fmt(s.avgSlippage5m),
                g.consecutiveNegativeFills,
                String.format("%.4f", s.fillShare5m));
    }

    // ── Strong cooldowns only (on fill) ───────────────────────────────────

    private void evaluateStrongCooldownOnly(MarketGuardState g, RollingSnapshot s, MarketGuardClassification cls) {
        if (g.status != GuardStatus.ACTIVE) {
            return;
        }

        GuardStatus prev = g.status;
        double shareTh = cfg.getGuardFillsShareThreshold();
        int maxNeg = cfg.getGuardMaxConsecutiveNegative();
        double hardPnl = cfg.getGuardHardCooldownPnl5mThreshold();

        boolean strongConsecutive = g.consecutiveNegativeFills >= maxNeg
                && s.pnl5m.doubleValue() <= hardPnl
                && cls != MarketGuardClassification.WINNER;

        boolean strongToxic = s.fills5m >= 3 && s.toxicRate5m >= cfg.getGuardToxicRateHardCooldown();

        boolean strongConcentrationLoss = s.fillShareSession > shareTh
                && g.sessionPnl.doubleValue() < 0
                && g.sessionFills >= 4;

        boolean strongChurn = s.staleCancelRate5m >= cfg.getGuardExtremeChurnStale5m()
                && s.staleEvents5m >= cfg.getGuardExtremeChurnMinStale()
                && s.fills5m <= 2;

        if (strongConsecutive || strongToxic || strongConcentrationLoss || strongChurn) {
            String reason = strongConsecutive ? "consecutive_negative_fills"
                    : strongToxic ? "toxic_rate_5m"
                    : strongConcentrationLoss ? "concentration_with_loss"
                    : "extreme_churn_5m";
            transitionHard(g, reason);
        } else if (cls == MarketGuardClassification.LOSER
                && s.pnl5m.doubleValue() < cfg.getGuardSoftCooldownPnl5mThreshold()
                && s.fills5m >= cfg.getGuardSoftCooldownMinFills5m()
                && !isProtectedWinner(s)) {
            transitionSoft(g, "rolling_pnl_5m_loser_sustained");
        }

        if (g.sessionPnl.doubleValue() < cfg.getGuardDisabledSessionPnlThreshold()
                && g.sessionFills >= cfg.getGuardDisabledMinSessionFills()) {
            transitionDisabled(g, "severe_session_loss");
        }

        if (prev != g.status) {
            logDecision(g, prev);
        }
    }

    private boolean isProtectedWinner(RollingSnapshot s) {
        return s.pnl5m.doubleValue() > 0 && s.toxicRate5m == 0 && s.fills5m >= cfg.getGuardWinnerMinFills5m();
    }

    private void transitionHard(MarketGuardState g, String reason) {
        g.status = GuardStatus.HARD_COOLDOWN;
        g.lastTransitionReason = reason;
        g.transitionCount++;
        g.cooldownUntilCycle = currentCycleApprox + cfg.getGuardHardCooldownCycles();
        g.cachedFinalPenalty = 0.0;
        log.info("[MARKET_GUARD_DECISION]\nmarket={}\naction=HARD_COOLDOWN\nreason={}\nclassification={}\nfinal_penalty=0.0000\ndisable_until={}",
                shortId(g.tokenId), reason, g.cachedClassification, g.cooldownUntilCycle);
    }

    private void transitionSoft(MarketGuardState g, String reason) {
        g.status = GuardStatus.SOFT_COOLDOWN;
        g.lastTransitionReason = reason;
        g.transitionCount++;
        g.cooldownUntilCycle = currentCycleApprox + cfg.getGuardSoftCooldownCycles();
        g.cachedFinalPenalty = 0.0;
        log.info("[MARKET_GUARD_DECISION]\nmarket={}\naction=SOFT_COOLDOWN\nreason={}\nclassification={}\nfinal_penalty=0.0000\ndisable_until={}",
                shortId(g.tokenId), reason, g.cachedClassification, g.cooldownUntilCycle);
    }

    private void transitionDisabled(MarketGuardState g, String reason) {
        if (g.status == GuardStatus.DISABLED_SESSION) return;
        g.status = GuardStatus.DISABLED_SESSION;
        g.lastTransitionReason = reason;
        g.transitionCount++;
        g.cooldownUntilCycle = 0;
        log.info("[MARKET_DISABLED]\nmarket={}\nreason={}\ndisable_until=session_end\nsession_fills={}\nsession_pnl={}",
                shortId(g.tokenId), reason, g.sessionFills, fmt(g.sessionPnl));
        log.info("[MARKET_GUARD_DECISION]\nmarket={}\naction=DISABLED_SESSION\nreason={}\nclassification={}\nfinal_penalty=0.0000\ndisable_until=session_end",
                shortId(g.tokenId), reason, g.cachedClassification);
    }

    private void logDecision(MarketGuardState g, GuardStatus prev) {
        RollingSnapshot snap = g.lastRollingSnapshot != null ? g.lastRollingSnapshot : buildRollingSnapshot(g);
        log.info("[MARKET_GUARD_STATE]\nmarket={}\nprev_state={}\nnew_state={}\nreason={}" +
                        "\nrolling_pnl_1m={}\nrolling_pnl_5m={}\nfills_share={}\nstale_cancel_rate={}" +
                        "\navg_slippage={}\nconsecutive_negative_fills={}",
                shortId(g.tokenId), prev, g.status, g.lastTransitionReason,
                fmt(snap.pnl1m), fmt(snap.pnl5m), fmtPct(snap.fillShareSession),
                fmtPct(snap.staleCancelRateSession), fmt(snap.avgSlippageSession), g.consecutiveNegativeFills);
    }

    // ── Quality snapshots ─────────────────────────────────────────────────

    public void logQualitySnapshots() {
        for (MarketGuardState g : guardStates.values()) {
            if (g.sessionFills == 0 && g.quoteAttempts == 0) continue;
            RollingSnapshot s = buildRollingSnapshot(g);
            log.info("[MARKET_QUALITY_SNAPSHOT]\nmarket={}\nclassification={}\nfill_rate_5m={}\nstale_cancel_rate_5m={}" +
                            "\ntoxic_rate_5m={}\nslippage_avg_5m={}\npnl_5m={}\nquote_count={}\nfill_count={}\nguard_penalty={}",
                    shortId(g.tokenId), g.cachedClassification.name(),
                    String.format("%.4f", s.fillRate),
                    String.format("%.4f", s.staleCancelRate5m),
                    String.format("%.4f", s.toxicRate5m),
                    fmt(s.avgSlippage5m),
                    fmt(s.pnl5m),
                    g.quoteAttempts, g.sessionFills,
                    String.format("%.4f", g.cachedFinalPenalty));
        }
    }

    // ── API summaries ─────────────────────────────────────────────────────

    public Map<String, Object> getGuardSummary() {
        Map<String, Object> summary = new LinkedHashMap<>();
        long soft = guardStates.values().stream().filter(g -> g.status == GuardStatus.SOFT_COOLDOWN).count();
        long hard = guardStates.values().stream().filter(g -> g.status == GuardStatus.HARD_COOLDOWN).count();
        long disabled = guardStates.values().stream().filter(g -> g.status == GuardStatus.DISABLED_SESSION).count();

        summary.put("marketsTracked", guardStates.size());
        summary.put("softCooldownCount", soft);
        summary.put("hardCooldownCount", hard);
        summary.put("disabledSessionCount", disabled);
        summary.put("totalTransitions", guardStates.values().stream().mapToLong(g -> g.transitionCount).sum());

        if (sessionFillsTotal > 0) {
            double hhi = guardStates.values().stream()
                    .mapToDouble(g -> Math.pow((double) g.sessionFills / sessionFillsTotal, 2))
                    .sum();
            summary.put("hhi", BigDecimal.valueOf(hhi).setScale(4, RoundingMode.HALF_UP));
            guardStates.values().stream().max(Comparator.comparingInt(g -> g.sessionFills)).ifPresent(g -> {
                summary.put("topFillsMarket", shortId(g.tokenId));
                summary.put("topFillsShare", BigDecimal.valueOf(
                        sessionFillsTotal > 0 ? (double) g.sessionFills / sessionFillsTotal : 0).setScale(4, RoundingMode.HALF_UP));
                summary.put("topFillsPnl", g.sessionPnl.setScale(4, RoundingMode.HALF_UP));
            });
        }

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
                    m.put("classification", g.cachedClassification.name());
                    m.put("sessionFills", g.sessionFills);
                    m.put("sessionPnl", g.sessionPnl.setScale(4, RoundingMode.HALF_UP));
                    m.put("fillsShare", BigDecimal.valueOf(
                            sessionFillsTotal > 0 ? (double) g.sessionFills / sessionFillsTotal : 0).setScale(4, RoundingMode.HALF_UP));
                    RollingSnapshot s = buildRollingSnapshot(g);
                    m.put("rollingPnl5m", s.pnl5m.setScale(4, RoundingMode.HALF_UP));
                    m.put("avgSlippage", s.avgSlippageSession.setScale(6, RoundingMode.HALF_UP));
                    m.put("staleCancelRate", BigDecimal.valueOf(s.staleCancelRateSession).setScale(4, RoundingMode.HALF_UP));
                    m.put("fillRate", BigDecimal.valueOf(s.fillRate).setScale(4, RoundingMode.HALF_UP));
                    m.put("consecutiveNegFills", g.consecutiveNegativeFills);
                    m.put("guardPenalty", BigDecimal.valueOf(g.cachedFinalPenalty).setScale(4, RoundingMode.HALF_UP));
                    m.put("penaltyBase", BigDecimal.valueOf(g.cachedBase).setScale(4, RoundingMode.HALF_UP));
                    m.put("penaltyLoss", BigDecimal.valueOf(g.cachedLoss).setScale(4, RoundingMode.HALF_UP));
                    m.put("penaltyToxicity", BigDecimal.valueOf(g.cachedToxicity).setScale(4, RoundingMode.HALF_UP));
                    m.put("penaltyChurn", BigDecimal.valueOf(g.cachedChurn).setScale(4, RoundingMode.HALF_UP));
                    m.put("penaltyConcentration", BigDecimal.valueOf(g.cachedConcentration).setScale(4, RoundingMode.HALF_UP));
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

    // ── Types ─────────────────────────────────────────────────────────────

    public enum GuardStatus {
        ACTIVE,
        SOFT_COOLDOWN,
        HARD_COOLDOWN,
        DISABLED_SESSION
    }

    @Data
    private static final class FillEvent {
        final Instant instant;
        final BigDecimal pnl;
        final boolean toxic;
        final BigDecimal slippageAbs;
    }

    private record RollingSnapshot(
            BigDecimal pnl1m,
            BigDecimal pnl5m,
            int fills5m,
            double toxicRate5m,
            double staleCancelRate5m,
            BigDecimal avgSlippage5m,
            double fillShare5m,
            double fillShareSession,
            double staleCancelRateSession,
            BigDecimal avgSlippageSession,
            double fillRate,
            int fills1m,
            int toxic5m,
            int staleEvents5m
    ) {}

    private static final class PenaltyBreakdown {
        double base;
        double loss;
        double toxicity;
        double churn;
        double concentration;
        double finalPenalty;
    }

    @Data
    public static class MarketGuardState {
        final String tokenId;
        GuardStatus status = GuardStatus.ACTIVE;
        long cooldownUntilCycle = 0;

        final List<FillEvent> fillEvents = new ArrayList<>();
        final List<Instant> staleCancelInstants = new ArrayList<>();

        int sessionFills;
        int fillsBuy;
        int fillsSell;
        int sessionToxicFills;
        BigDecimal sessionPnl = BigDecimal.ZERO;
        BigDecimal totalSlippage = BigDecimal.ZERO;
        int cancelCount;
        int staleCancelCount;
        int quoteAttempts;
        int consecutiveNegativeFills;
        int transitionCount;
        String lastTransitionReason = "none";

        MarketGuardClassification cachedClassification = MarketGuardClassification.NEUTRAL;
        double cachedFinalPenalty;
        double cachedBase;
        double cachedLoss;
        double cachedToxicity;
        double cachedChurn;
        double cachedConcentration;
        RollingSnapshot lastRollingSnapshot;

        MarketGuardClassification lastLoggedClassification = MarketGuardClassification.NEUTRAL;
        double lastLoggedPenalty = -1.0;

        MarketGuardState(String tokenId) {
            this.tokenId = tokenId;
        }
    }

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
