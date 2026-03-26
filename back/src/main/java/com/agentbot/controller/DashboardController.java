package com.agentbot.controller;

import com.agentbot.model.*;
import com.agentbot.service.DashboardService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class DashboardController {

    private final DashboardService dashboardService;

    @GetMapping("/status")
    public StatusDto getStatus() {
        return dashboardService.getStatus();
    }

    @GetMapping("/orders")
    public List<Order> getOrders() {
        return dashboardService.getOrders();
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
}
