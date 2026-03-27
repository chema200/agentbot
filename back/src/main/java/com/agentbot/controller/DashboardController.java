package com.agentbot.controller;

import com.agentbot.engine.*;
import com.agentbot.engine.model.EngineFill;
import com.agentbot.model.*;
import com.agentbot.service.DashboardService;
import com.agentbot.shadow.ShadowComparisonMetrics;
import com.agentbot.shadow.ShadowTradingEngine;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class DashboardController {

    private final DashboardService dashboardService;
    private final TradingEngine tradingEngine;
    private final ShadowTradingEngine shadowEngine;
    private final ShadowComparisonMetrics shadowMetrics;
    private final PnLService pnlService;
    private final InventoryManager inventoryManager;
    private final QuoteSupervisor quoteSupervisor;

    private static final DateTimeFormatter TS_FMT = DateTimeFormatter
            .ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());

    @GetMapping("/status")
    public StatusDto getStatus() {
        return dashboardService.getStatus();
    }

    @GetMapping("/orders")
    public List<Order> getOrders() {
        return dashboardService.getOrders();
    }

    @GetMapping("/orders/{orderId}")
    public ResponseEntity<OrderDetailDto> getOrderDetail(@PathVariable String orderId) {
        OrderDetailDto detail = dashboardService.getOrderDetail(orderId);
        if (detail == null) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(detail);
    }

    @GetMapping("/fills")
    public List<Fill> getFills() {
        return dashboardService.getFills();
    }

    @GetMapping("/inventory")
    public InventoryDto getInventory() {
        return dashboardService.getInventory();
    }

    @GetMapping("/pnl")
    public PnlDto getPnl() {
        return dashboardService.getPnl();
    }

    @GetMapping("/markets")
    public List<MarketDto> getMarkets() {
        return dashboardService.getMarkets();
    }

    @PostMapping("/engine/start")
    public ResponseEntity<Map<String, String>> startEngine() {
        dashboardService.startEngine();
        return ResponseEntity.ok(Map.of("status", "STARTED"));
    }

    @PostMapping("/engine/pause")
    public ResponseEntity<Map<String, String>> pauseEngine() {
        dashboardService.pauseEngine();
        return ResponseEntity.ok(Map.of("status", "PAUSED"));
    }

    @PostMapping("/engine/stop")
    public ResponseEntity<Map<String, String>> stopEngine() {
        dashboardService.stopEngine();
        return ResponseEntity.ok(Map.of("status", "STOPPED"));
    }

    @GetMapping(value = "/debug/export-logs", produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<String> exportTradingLogs() {
        StringBuilder sb = new StringBuilder();
        sb.append("=== AGENTBOT TRADING LOG EXPORT ===\n\n");

        for (Path p : List.of(Path.of("logs/trading-engine.log"), Path.of("logs/app.log"))) {
            if (Files.exists(p)) {
                try {
                    List<String> lines = Files.readAllLines(p);
                    int start = Math.max(0, lines.size() - 5000);
                    sb.append("--- ").append(p.getFileName()).append(" (last ")
                      .append(lines.size() - start).append(" lines) ---\n");
                    for (int i = start; i < lines.size(); i++) sb.append(lines.get(i)).append('\n');
                    sb.append('\n');
                } catch (IOException e) {
                    sb.append("Error reading ").append(p).append(": ").append(e.getMessage()).append('\n');
                }
            } else {
                sb.append("File not found: ").append(p).append('\n');
            }
        }
        return ResponseEntity.ok(sb.toString());
    }

    @GetMapping(value = "/debug/validation-summary", produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<String> validationSummary() {
        Instant now = Instant.now();
        StringBuilder sb = new StringBuilder();
        sb.append("=== VALIDATION SUMMARY ===\n\n");

        // --- Session info ---
        Instant shadowStart = shadowEngine.getStartedAt();
        sb.append("session_start_shadow=").append(shadowStart != null ? TS_FMT.format(shadowStart) : "N/A").append('\n');
        sb.append("session_end=").append(TS_FMT.format(now)).append('\n');
        long durShadow = shadowStart != null ? Duration.between(shadowStart, now).getSeconds() : 0;
        sb.append("duration_sec_shadow=").append(durShadow).append('\n');
        sb.append('\n');

        // --- Real engine ---
        sb.append("=== REAL ENGINE ===\n");
        sb.append("state=").append(tradingEngine.getState()).append('\n');
        sb.append("cycles_total_real=").append(tradingEngine.getCycleCount()).append('\n');

        List<EngineFill> realFills = quoteSupervisor.getFills();
        long realToxic = realFills.stream().filter(EngineFill::isToxicFlow).count();
        sb.append("fills_total_real=").append(realFills.size()).append('\n');
        sb.append("fills_toxic_real=").append(realToxic).append('\n');
        sb.append("pnl_trading_real=").append(pnlService.getTradingPnl().setScale(4, RoundingMode.HALF_UP)).append('\n');
        sb.append("pnl_reward_real=").append(pnlService.getTotalRewardPnl().setScale(4, RoundingMode.HALF_UP)).append('\n');
        sb.append("pnl_total_real=").append(pnlService.getTotalPnl().setScale(4, RoundingMode.HALF_UP)).append('\n');
        sb.append("fees_real=").append(pnlService.getTotalFees().setScale(6, RoundingMode.HALF_UP)).append('\n');
        sb.append("yes_exposure_real=").append(inventoryManager.getTotalYesExposure().setScale(2, RoundingMode.HALF_UP)).append('\n');
        sb.append("no_exposure_real=").append(inventoryManager.getTotalNoExposure().setScale(2, RoundingMode.HALF_UP)).append('\n');
        sb.append("net_exposure_real=").append(inventoryManager.getGlobalNetExposure().setScale(2, RoundingMode.HALF_UP)).append('\n');
        sb.append("max_cap_observed_real=").append(tradingEngine.getMaxCapSeen().setScale(4, RoundingMode.HALF_UP)).append('\n');
        sb.append("cap_violations_real=").append(tradingEngine.getCapViolationCount()).append('\n');
        sb.append("cooldowns_triggered_real=").append(tradingEngine.getCooldownsTriggered()).append('\n');
        sb.append("cooldowns_active_real=").append(tradingEngine.getCooldownsActive()).append('\n');
        sb.append('\n');

        // --- Shadow engine ---
        sb.append("=== SHADOW ENGINE ===\n");
        sb.append("status=").append(shadowEngine.getStatus()).append('\n');
        sb.append("cycles_total_shadow=").append(shadowEngine.getCycleCount()).append('\n');
        sb.append("fills_total_shadow=").append(shadowMetrics.getTotalFills()).append('\n');
        sb.append("fills_list_size_shadow=").append(shadowEngine.getFills().size()).append('\n');
        sb.append("fills_toxic_shadow=").append(shadowMetrics.getToxicFills()).append('\n');
        sb.append("pnl_trading_shadow=").append(shadowMetrics.getTotalPnl().setScale(4, RoundingMode.HALF_UP)).append('\n');
        sb.append("pnl_reward_shadow=0.0000\n");
        sb.append("pnl_total_shadow=").append(shadowMetrics.getTotalPnl().setScale(4, RoundingMode.HALF_UP)).append('\n');
        sb.append("fees_shadow=").append(shadowMetrics.getTotalFees().setScale(6, RoundingMode.HALF_UP)).append('\n');
        sb.append("max_yes_exposure_shadow=").append(shadowMetrics.getMaxYes().setScale(4, RoundingMode.HALF_UP)).append('\n');
        sb.append("max_no_exposure_shadow=").append(shadowMetrics.getMaxNo().setScale(4, RoundingMode.HALF_UP)).append('\n');
        sb.append("max_net_exposure_shadow=").append(shadowMetrics.getMaxNet().setScale(4, RoundingMode.HALF_UP)).append('\n');
        sb.append("max_drawdown_shadow=").append(shadowMetrics.getMaxDrawdown().setScale(4, RoundingMode.HALF_UP)).append('\n');
        sb.append("cap_violations_shadow=").append(shadowEngine.getCapViolationCount().get()).append('\n');
        sb.append("cooldowns_triggered_shadow=").append(shadowEngine.getCooldownsTriggered().get()).append('\n');
        sb.append("order_rejects_shadow=").append(shadowEngine.getOrderRejectCount().get()).append('\n');
        sb.append("live_markets_shadow=").append(shadowEngine.getLiveMarkets().size()).append('\n');
        sb.append("active_orders_shadow=").append(shadowEngine.getActiveOrders().size()).append('\n');
        sb.append("ws_connected_shadow=").append(shadowEngine.isWsConnected()).append('\n');
        sb.append('\n');

        // --- Consistency checks ---
        sb.append("=== CONSISTENCY CHECKS ===\n");
        int metrFills = shadowMetrics.getTotalFills();
        int listFills = shadowEngine.getFills().size();
        sb.append("fills_metrics_vs_list=").append(metrFills == listFills ? "OK" : "MISMATCH")
          .append(" (metrics=").append(metrFills).append(" list=").append(listFills).append(")\n");
        sb.append("cap_violations_zero_real=").append(tradingEngine.getCapViolationCount() == 0 ? "OK" : "FAIL").append('\n');
        sb.append("cap_violations_zero_shadow=").append(shadowEngine.getCapViolationCount().get() == 0 ? "OK" : "FAIL").append('\n');

        // --- Per-market table (shadow) ---
        sb.append('\n');
        sb.append("=== PER-MARKET TABLE (SHADOW) ===\n");
        sb.append(String.format("%-18s %-50s %6s %6s %10s %10s %8s %8s %8s %7s\n",
                "TOKEN_ID", "QUESTION", "FILLS", "TOXIC", "PNL", "SLIPPAGE", "BID", "ASK", "MID", "REGIME"));
        sb.append("-".repeat(140)).append('\n');

        var perToken = shadowMetrics.getPerTokenMetrics();
        var allTokens = new java.util.ArrayList<>(perToken.entrySet());
        allTokens.sort((a, b) -> b.getValue().getPnl().compareTo(a.getValue().getPnl()));

        for (var entry : allTokens) {
            String tokenId = entry.getKey();
            var tm = entry.getValue();
            var lms = shadowEngine.getLiveMarkets().get(tokenId);
            String question = lms != null ? lms.getQuestion() : "?";
            if (question.length() > 48) question = question.substring(0, 48) + "..";
            String regime = lms != null ? lms.getRegime().name() : "?";
            String bid = lms != null ? lms.getBestBid().get().setScale(4, RoundingMode.HALF_UP).toPlainString() : "?";
            String ask = lms != null ? lms.getBestAsk().get().setScale(4, RoundingMode.HALF_UP).toPlainString() : "?";
            String mid = lms != null ? lms.getMidPrice().setScale(4, RoundingMode.HALF_UP).toPlainString() : "?";

            sb.append(String.format("%-18s %-50s %6d %6d %10s %10s %8s %8s %8s %7s\n",
                    shortId(tokenId), question,
                    tm.getFills(), tm.getToxicFills(),
                    tm.getPnl().setScale(4, RoundingMode.HALF_UP),
                    tm.getTotalSlippage().setScale(4, RoundingMode.HALF_UP),
                    bid, ask, mid, regime));
        }

        // --- Live markets not traded ---
        sb.append('\n');
        sb.append("=== LIVE MARKETS NOT TRADED (SHADOW) ===\n");
        for (var lms : shadowEngine.getLiveMarkets().values()) {
            if (!perToken.containsKey(lms.getTokenId())) {
                String question = lms.getQuestion();
                if (question.length() > 60) question = question.substring(0, 60) + "..";
                sb.append(String.format("%-18s mid=%-8s spread=%-8s regime=%-8s %s\n",
                        shortId(lms.getTokenId()),
                        lms.getMidPrice().setScale(4, RoundingMode.HALF_UP),
                        lms.getSpread().setScale(4, RoundingMode.HALF_UP),
                        lms.getRegime().name(),
                        question));
            }
        }

        // --- Write to file ---
        try {
            Path dir = Path.of("logs");
            if (!Files.exists(dir)) Files.createDirectories(dir);
            Files.writeString(Path.of("logs/validation-summary.txt"), sb.toString());
        } catch (IOException e) {
            sb.append("\n\n[ERROR writing file: ").append(e.getMessage()).append("]\n");
        }

        return ResponseEntity.ok(sb.toString());
    }

    @GetMapping(value = "/debug/structured-logs", produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<String> structuredLogs() {
        StringBuilder sb = new StringBuilder();
        sb.append("=== AGENTBOT STRUCTURED LOG EXPORT ===\n");
        sb.append("generated=").append(TS_FMT.format(Instant.now())).append("\n\n");

        String[] tags = {"SHADOW_CYCLE_SUMMARY", "CYCLE_SUMMARY", "SHADOW_FILL", "CANCEL",
                "ORDER_REJECT", "CAP_CLAMP", "CAP_VIOLATION", "REGIME_BLOCK",
                "COOLDOWN_START", "COOLDOWN_END", "EDGE_CLAMP", "REAL_SNAPSHOT",
                "METRICS_INCONSISTENCY", "SIM_CYCLE_SUMMARY",
                "MARKET_GUARD_STATE", "MARKET_GUARD_PENALTY", "MARKET_QUALITY_SNAPSHOT", "MARKET_DISABLED"};

        for (Path logFile : List.of(
                Path.of("logs/shadow-engine.log"),
                Path.of("logs/trading-engine.log"))) {
            if (!Files.exists(logFile)) {
                sb.append("--- ").append(logFile.getFileName()).append(": NOT FOUND ---\n\n");
                continue;
            }
            sb.append("--- ").append(logFile.getFileName()).append(" ---\n");
            try {
                List<String> lines = Files.readAllLines(logFile);
                for (int i = 0; i < lines.size(); i++) {
                    String line = lines.get(i);
                    boolean isTagged = false;
                    for (String tag : tags) {
                        if (line.contains("[" + tag + "]")) { isTagged = true; break; }
                    }
                    if (isTagged) {
                        sb.append(line).append('\n');
                        for (int j = i + 1; j < lines.size(); j++) {
                            String next = lines.get(j);
                            if (next.isEmpty() || next.startsWith("202")) break;
                            sb.append(next).append('\n');
                        }
                        sb.append('\n');
                    }
                }
            } catch (IOException e) {
                sb.append("Error: ").append(e.getMessage()).append('\n');
            }
            sb.append('\n');
        }

        try {
            Path dir = Path.of("logs");
            if (!Files.exists(dir)) Files.createDirectories(dir);
            Files.writeString(Path.of("logs/structured-events.txt"), sb.toString());
        } catch (IOException ignored) {}

        return ResponseEntity.ok(sb.toString());
    }

    private static String shortId(String id) {
        return id != null && id.length() > 16 ? id.substring(0, 16) : (id != null ? id : "null");
    }
}
