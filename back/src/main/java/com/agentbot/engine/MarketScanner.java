package com.agentbot.engine;

import com.agentbot.engine.model.SimulatedMarket;
import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
@RequiredArgsConstructor
public class MarketScanner {

    private final MarketDataEngine marketDataEngine;

    @Getter
    private final Map<String, SimulatedMarket> markets = new ConcurrentHashMap<>();

    @PostConstruct
    public void init() {
        seedMarkets();
        log.info("MarketScanner initialized with {} markets", markets.size());
    }

    private void seedMarkets() {
        addMarket("mkt-btc100k", "Will Bitcoin hit $100k by Dec 2026?", "0.62");
        addMarket("mkt-gdp-q2", "US GDP growth > 3% in Q2?", "0.45");
        addMarket("mkt-fed-cut", "Fed rate cut before July 2026?", "0.71");
        addMarket("mkt-eth-merge", "Ethereum above $5k by EOY?", "0.36");
        addMarket("mkt-trump28", "Trump wins 2028 election?", "0.48");
        addMarket("mkt-starship", "SpaceX Starship orbital success?", "0.82");
        addMarket("mkt-ai-agi", "AGI achieved before 2030?", "0.15");
        addMarket("mkt-sp500", "S&P 500 above 6000 by EOY?", "0.55");
        addMarket("mkt-inflation", "US inflation below 2% in 2026?", "0.40");
        addMarket("mkt-usd-eur", "EUR/USD above 1.15 by Q4?", "0.30");
    }

    private void addMarket(String id, String name, String midPrice) {
        markets.put(id, new SimulatedMarket(id, name, new BigDecimal(midPrice)));
    }

    public void tickAll() {
        markets.values().forEach(marketDataEngine::tick);
    }

    public List<SimulatedMarket> getAllMarkets() {
        return List.copyOf(markets.values());
    }

    public SimulatedMarket getMarket(String marketId) {
        return markets.get(marketId);
    }

    public void resetMarkets() {
        markets.clear();
        seedMarkets();
    }
}
