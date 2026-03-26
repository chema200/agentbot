package com.agentbot.service;

import com.agentbot.engine.*;
import com.agentbot.engine.model.*;
import com.agentbot.model.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class DashboardService {

    private final TradingEngine tradingEngine;
    private final MarketScanner marketScanner;
    private final MarketRankingEngine rankingEngine;
    private final OrderManager orderManager;
    private final QuoteSupervisor quoteSupervisor;
    private final InventoryManager inventoryManager;
    private final RiskManager riskManager;
    private final PnLService pnlService;
    private final RewardEngine rewardEngine;

    public StatusDto getStatus() {
        TradingEngineState engineState = tradingEngine.getState();
        return StatusDto.builder()
                .botStatus(engineState.name())
                .connection(engineState == TradingEngineState.ERROR ? "ERROR" : "OK")
                .uptime(tradingEngine.getCycleCount())
                .build();
    }

    public List<Order> getOrders() {
        return orderManager.getAllOrders().stream()
                .map(this::toOrderDto)
                .collect(Collectors.toList());
    }

    public List<Fill> getFills() {
        List<EngineFill> fills = quoteSupervisor.getFills();
        int start = Math.max(0, fills.size() - 50);
        return fills.subList(start, fills.size()).stream()
                .map(this::toFillDto)
                .collect(Collectors.toList());
    }

    public InventoryDto getInventory() {
        return InventoryDto.builder()
                .yesExposure(inventoryManager.getTotalYesExposure().setScale(2, RoundingMode.HALF_UP))
                .noExposure(inventoryManager.getTotalNoExposure().setScale(2, RoundingMode.HALF_UP))
                .netExposure(inventoryManager.getGlobalNetExposure().setScale(2, RoundingMode.HALF_UP))
                .build();
    }

    public PnlDto getPnl() {
        BigDecimal unrealized = pnlService.getUnrealizedPnl(
                inventoryManager.getAllPositions(),
                marketScanner::getMarket);
        BigDecimal tradingPnl = pnlService.getTradingPnl();
        BigDecimal rewardPnl = pnlService.getTotalRewardPnl();
        BigDecimal totalPnl = tradingPnl.add(rewardPnl);
        return PnlDto.builder()
                .realized(tradingPnl.setScale(2, RoundingMode.HALF_UP))
                .unrealized(unrealized.setScale(2, RoundingMode.HALF_UP))
                .daily(totalPnl.add(unrealized).setScale(2, RoundingMode.HALF_UP))
                .tradingPnl(tradingPnl.setScale(2, RoundingMode.HALF_UP))
                .rewardPnl(rewardPnl.setScale(2, RoundingMode.HALF_UP))
                .totalPnl(totalPnl.setScale(2, RoundingMode.HALF_UP))
                .fees(pnlService.getTotalFees().setScale(4, RoundingMode.HALF_UP))
                .build();
    }

    public List<MarketDto> getMarkets() {
        List<MarketScore> fullRanking = rankingEngine.getLatestFullRanking();
        Map<String, MarketScore> scoreMap = fullRanking.stream()
                .collect(Collectors.toMap(MarketScore::getMarketId, s -> s, (a, b) -> a));

        return marketScanner.getAllMarkets().stream()
                .map(sm -> toMarketDto(sm, scoreMap.get(sm.getMarketId())))
                .collect(Collectors.toList());
    }

    public void startEngine() { tradingEngine.start(); }
    public void pauseEngine() { tradingEngine.pause(); }
    public void stopEngine() { tradingEngine.stop(); }

    private Order toOrderDto(EngineOrder eo) {
        Order.OrderStatus status = switch (eo.getStatus()) {
            case OPEN, PARTIALLY_FILLED -> Order.OrderStatus.OPEN;
            case FILLED -> Order.OrderStatus.FILLED;
            case CANCELLED -> Order.OrderStatus.CANCELLED;
        };
        Order.Side side = eo.getSide() == EngineOrder.Side.BUY ? Order.Side.BUY : Order.Side.SELL;

        return Order.builder()
                .id(Math.abs(eo.getOrderId().hashCode()) % 100000L)
                .market(eo.getMarketName())
                .side(side)
                .price(eo.getPrice())
                .size(eo.getOriginalSize())
                .status(status)
                .createdAt(eo.getCreatedAt())
                .build();
    }

    private Fill toFillDto(EngineFill ef) {
        Order.Side side = ef.getSide() == EngineOrder.Side.BUY ? Order.Side.BUY : Order.Side.SELL;
        return Fill.builder()
                .id(Math.abs(ef.getFillId().hashCode()) % 100000L)
                .market(ef.getMarketName())
                .side(side)
                .price(ef.getPrice())
                .size(ef.getSize())
                .fee(ef.getFee())
                .filledAt(ef.getFilledAt())
                .build();
    }

    private MarketDto toMarketDto(SimulatedMarket sm, MarketScore ms) {
        MarketDto.MarketDtoBuilder builder = MarketDto.builder()
                .marketId(sm.getMarketId())
                .name(sm.getName())
                .bestBid(sm.getBestBid())
                .bestAsk(sm.getBestAsk())
                .spread(sm.getSpread())
                .volume(sm.getVolume().setScale(0, RoundingMode.HALF_UP))
                .liquidityScore(sm.getLiquidityScore().setScale(1, RoundingMode.HALF_UP))
                .regime(sm.getRegime().name());

        if (ms != null) {
            builder.edgeScore(ms.getEdgeScore())
                    .rewardEfficiency(ms.getRewardEfficiency())
                    .competitionDensity(ms.getCompetitionDensity())
                    .volatilityPenalty(ms.getVolatilityPenalty())
                    .selected(ms.isSelected());
        }

        return builder.build();
    }
}
