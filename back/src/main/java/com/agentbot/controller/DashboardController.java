package com.agentbot.controller;

import com.agentbot.engine.TradingEngine;
import com.agentbot.model.*;
import com.agentbot.service.DashboardService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class DashboardController {

    private final DashboardService dashboardService;
    private final TradingEngine tradingEngine;

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
        StringBuilder sb = new StringBuilder();
        sb.append("=== VALIDATION SUMMARY ===\n\n");
        sb.append("max_cap_observed_real=").append(tradingEngine.getMaxCapSeen().setScale(4, java.math.RoundingMode.HALF_UP)).append('\n');
        sb.append("cap_violations_real=").append(tradingEngine.getCapViolationCount()).append('\n');
        sb.append("cooldowns_triggered=").append(tradingEngine.getCooldownsTriggered()).append('\n');
        sb.append("cooldowns_active=").append(tradingEngine.getCooldownsActive()).append('\n');
        sb.append("cycles=").append(tradingEngine.getCycleCount()).append('\n');
        sb.append("state=").append(tradingEngine.getState()).append('\n');
        return ResponseEntity.ok(sb.toString());
    }
}
