package com.agentbot.controller;

import com.agentbot.model.*;
import com.agentbot.shadow.*;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.math.RoundingMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/shadow")
@RequiredArgsConstructor
public class ShadowController {

    private final ShadowTradingEngine shadowEngine;
    private final ShadowComparisonMetrics metrics;
    private final ShadowMarketGuardService guardService;

    @PostMapping("/start")
    public ResponseEntity<Map<String, String>> start() {
        shadowEngine.start();
        return ResponseEntity.ok(Map.of("status", "STARTED"));
    }

    @PostMapping("/stop")
    public ResponseEntity<Map<String, String>> stop() {
        shadowEngine.stop();
        return ResponseEntity.ok(Map.of("status", "STOPPED"));
    }

    @GetMapping("/status")
    public ShadowStatusDto getStatus() {
        return ShadowStatusDto.builder()
                .status(shadowEngine.getStatus())
                .wsConnected(shadowEngine.isWsConnected())
                .cycleCount(shadowEngine.getCycleCount())
                .liveMarkets(shadowEngine.getLiveMarkets().size())
                .activeOrders(shadowEngine.getActiveOrders().size())
                .totalFills(shadowEngine.getFills().size())
                .startedAt(shadowEngine.getStartedAt())
                .metrics(metrics.getSummary())
                .build();
    }

    @GetMapping("/markets")
    public List<ShadowMarketDto> getMarkets() {
        return shadowEngine.getLiveMarkets().values().stream()
                .map(s -> ShadowMarketDto.builder()
                        .tokenId(s.getTokenId())
                        .question(s.getQuestion())
                        .outcome(s.getOutcome())
                        .liveBestBid(s.getBestBid().get().setScale(4, RoundingMode.HALF_UP))
                        .liveBestAsk(s.getBestAsk().get().setScale(4, RoundingMode.HALF_UP))
                        .liveMid(s.getMidPrice())
                        .liveSpread(s.getSpread().setScale(4, RoundingMode.HALF_UP))
                        .tradeCount(s.getTradeCount())
                        .lastUpdate(s.getLastUpdateTime().get())
                        .regime(s.getRegime().name())
                        .build())
                .collect(Collectors.toList());
    }

    @GetMapping("/orders")
    public List<ShadowOrderDto> getOrders() {
        return shadowEngine.getActiveOrders().stream()
                .map(this::toOrderDto)
                .collect(Collectors.toList());
    }

    @GetMapping("/fills")
    public List<ShadowFillDto> getFills() {
        List<ShadowFill> fills = shadowEngine.getFills();
        int start = Math.max(0, fills.size() - 50);
        return fills.subList(start, fills.size()).stream()
                .map(this::toFillDto)
                .collect(Collectors.toList());
    }

    @GetMapping("/pnl")
    public Map<String, Object> getPnl() {
        return metrics.getSummary();
    }

    @GetMapping("/guard/summary")
    public Map<String, Object> getGuardSummary() {
        return guardService.getGuardSummary();
    }

    @GetMapping("/guard/markets")
    public List<Map<String, Object>> getGuardMarkets() {
        return guardService.getPerMarketGuardData();
    }

    @GetMapping(value = "/debug/export-logs", produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<String> exportLogs() {
        StringBuilder sb = new StringBuilder();
        sb.append("=== AGENTBOT SHADOW LOG EXPORT ===\n\n");

        Path reviewLog = Path.of("logs/agentbot-review.log");
        Path shadowLog = Path.of("logs/shadow-engine.log");

        for (Path p : List.of(reviewLog, shadowLog)) {
            if (Files.exists(p)) {
                try {
                    List<String> lines = Files.readAllLines(p);
                    int start = Math.max(0, lines.size() - 5000);
                    sb.append("--- ").append(p.getFileName()).append(" (last ").append(lines.size() - start).append(" lines) ---\n");
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

    private ShadowOrderDto toOrderDto(ShadowOrder o) {
        return ShadowOrderDto.builder()
                .orderId(o.getOrderId())
                .tokenId(o.getTokenId())
                .question(o.getMarketQuestion())
                .outcome(o.getOutcome())
                .side(o.getSide())
                .price(o.getPrice())
                .size(o.getSize())
                .status(o.getStatus())
                .createdAt(o.getCreatedAt())
                .liveBestBid(o.getLiveBestBid())
                .liveBestAsk(o.getLiveBestAsk())
                .liveMid(o.getLiveMid())
                .edgeScore(o.getEdgeScore())
                .capitalShare(o.getCapitalShare())
                .regime(o.getRegime())
                .build();
    }

    private ShadowFillDto toFillDto(ShadowFill f) {
        return ShadowFillDto.builder()
                .fillId(f.getFillId())
                .tokenId(f.getTokenId())
                .question(f.getMarketQuestion())
                .outcome(f.getOutcome())
                .side(f.getSide())
                .fillPrice(f.getFillPrice())
                .fillSize(f.getFillSize())
                .fee(f.getFee())
                .slippage(f.getSlippage())
                .midAtFill(f.getMidAtFill())
                .liveBidAtFill(f.getLiveBidAtFill())
                .liveAskAtFill(f.getLiveAskAtFill())
                .toxic(f.isWouldHaveBeenToxic())
                .estimatedPnl(f.getEstimatedPnl())
                .filledAt(f.getFilledAt())
                .build();
    }
}
