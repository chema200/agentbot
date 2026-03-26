package com.agentbot.controller;

import com.agentbot.model.*;
import com.agentbot.service.MockDataService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class DashboardController {

    private final MockDataService mockDataService;

    @GetMapping("/status")
    public StatusDto getStatus() {
        return mockDataService.getStatus();
    }

    @GetMapping("/orders")
    public List<Order> getOrders() {
        return mockDataService.getOrders();
    }

    @GetMapping("/fills")
    public List<Fill> getFills() {
        return mockDataService.getFills();
    }

    @GetMapping("/inventory")
    public InventoryDto getInventory() {
        return mockDataService.getInventory();
    }

    @GetMapping("/pnl")
    public PnlDto getPnl() {
        return mockDataService.getPnl();
    }

    @GetMapping("/markets")
    public List<MarketDto> getMarkets() {
        return mockDataService.getMarkets();
    }
}
