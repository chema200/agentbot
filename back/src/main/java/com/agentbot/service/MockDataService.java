package com.agentbot.service;

import com.agentbot.model.*;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

@Service
public class MockDataService {

    public StatusDto getStatus() {
        return StatusDto.builder()
                .botStatus("RUNNING")
                .connection("OK")
                .uptime(System.currentTimeMillis() / 1000)
                .build();
    }

    public List<Order> getOrders() {
        return List.of(
                Order.builder().id(1L).market("Will Bitcoin hit $100k by Dec 2026?").side(Order.Side.BUY).price(bd("0.62")).size(bd("150")).status(Order.OrderStatus.OPEN).createdAt(Instant.now().minusSeconds(3600)).build(),
                Order.builder().id(2L).market("US GDP growth > 3% in Q2?").side(Order.Side.SELL).price(bd("0.45")).size(bd("200")).status(Order.OrderStatus.OPEN).createdAt(Instant.now().minusSeconds(7200)).build(),
                Order.builder().id(3L).market("Fed rate cut before July 2026?").side(Order.Side.BUY).price(bd("0.71")).size(bd("100")).status(Order.OrderStatus.FILLED).createdAt(Instant.now().minusSeconds(86400)).build(),
                Order.builder().id(4L).market("Ethereum merge upgrade successful?").side(Order.Side.SELL).price(bd("0.33")).size(bd("300")).status(Order.OrderStatus.CANCELLED).createdAt(Instant.now().minusSeconds(172800)).build(),
                Order.builder().id(5L).market("Trump wins 2028 election?").side(Order.Side.BUY).price(bd("0.48")).size(bd("250")).status(Order.OrderStatus.OPEN).createdAt(Instant.now().minusSeconds(1800)).build(),
                Order.builder().id(6L).market("SpaceX Starship orbital success?").side(Order.Side.BUY).price(bd("0.82")).size(bd("175")).status(Order.OrderStatus.FILLED).createdAt(Instant.now().minusSeconds(43200)).build()
        );
    }

    public List<Fill> getFills() {
        return List.of(
                Fill.builder().id(1L).market("Fed rate cut before July 2026?").side(Order.Side.BUY).price(bd("0.71")).size(bd("100")).fee(bd("0.50")).filledAt(Instant.now().minusSeconds(3600)).build(),
                Fill.builder().id(2L).market("SpaceX Starship orbital success?").side(Order.Side.BUY).price(bd("0.82")).size(bd("175")).fee(bd("0.88")).filledAt(Instant.now().minusSeconds(7200)).build(),
                Fill.builder().id(3L).market("US GDP growth > 3% in Q2?").side(Order.Side.SELL).price(bd("0.44")).size(bd("120")).fee(bd("0.60")).filledAt(Instant.now().minusSeconds(14400)).build()
        );
    }

    public InventoryDto getInventory() {
        return InventoryDto.builder()
                .yesExposure(bd("425.00"))
                .noExposure(bd("320.00"))
                .netExposure(bd("105.00"))
                .build();
    }

    public PnlDto getPnl() {
        return PnlDto.builder()
                .realized(bd("234.50"))
                .unrealized(bd("-45.20"))
                .daily(bd("67.80"))
                .build();
    }

    public List<MarketDto> getMarkets() {
        return List.of(
                MarketDto.builder().marketId("mkt-001").name("Will Bitcoin hit $100k by Dec 2026?").bestBid(bd("0.61")).bestAsk(bd("0.63")).spread(bd("0.02")).volume(bd("1245000")).liquidityScore(bd("8.5")).build(),
                MarketDto.builder().marketId("mkt-002").name("US GDP growth > 3% in Q2?").bestBid(bd("0.43")).bestAsk(bd("0.47")).spread(bd("0.04")).volume(bd("890000")).liquidityScore(bd("7.2")).build(),
                MarketDto.builder().marketId("mkt-003").name("Fed rate cut before July 2026?").bestBid(bd("0.70")).bestAsk(bd("0.73")).spread(bd("0.03")).volume(bd("2100000")).liquidityScore(bd("9.1")).build(),
                MarketDto.builder().marketId("mkt-004").name("Trump wins 2028 election?").bestBid(bd("0.47")).bestAsk(bd("0.50")).spread(bd("0.03")).volume(bd("3500000")).liquidityScore(bd("9.5")).build(),
                MarketDto.builder().marketId("mkt-005").name("SpaceX Starship orbital success?").bestBid(bd("0.80")).bestAsk(bd("0.84")).spread(bd("0.04")).volume(bd("675000")).liquidityScore(bd("6.8")).build(),
                MarketDto.builder().marketId("mkt-006").name("Ethereum above $5k by EOY?").bestBid(bd("0.35")).bestAsk(bd("0.38")).spread(bd("0.03")).volume(bd("1560000")).liquidityScore(bd("8.0")).build()
        );
    }

    private static BigDecimal bd(String val) {
        return new BigDecimal(val);
    }
}
