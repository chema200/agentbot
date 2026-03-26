package com.agentbot.engine;

import com.agentbot.engine.model.MarketScore;
import com.agentbot.engine.model.SimulatedMarket;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

@Slf4j
@Component
public class MarketRankingEngine {

    private static final BigDecimal REWARD_WEIGHT = new BigDecimal("0.30");
    private static final BigDecimal SPREAD_WEIGHT = new BigDecimal("0.25");
    private static final BigDecimal COMPETITION_WEIGHT = new BigDecimal("0.15");
    private static final BigDecimal LIQUIDITY_WEIGHT = new BigDecimal("0.20");
    private static final BigDecimal RISK_WEIGHT = new BigDecimal("0.10");

    public List<MarketScore> rankMarkets(List<SimulatedMarket> markets) {
        AtomicInteger rankCounter = new AtomicInteger(1);

        return markets.stream()
                .map(this::scoreMarket)
                .sorted(Comparator.comparing(MarketScore::getTotalScore).reversed())
                .peek(ms -> ms.setRank(rankCounter.getAndIncrement()))
                .collect(Collectors.toList());
    }

    public List<MarketScore> getTopMarkets(List<SimulatedMarket> markets, int topN) {
        return rankMarkets(markets).stream()
                .limit(topN)
                .collect(Collectors.toList());
    }

    private MarketScore scoreMarket(SimulatedMarket market) {
        BigDecimal rewardComp = REWARD_WEIGHT.multiply(market.getRewardScore());

        BigDecimal spreadInverse = BigDecimal.ONE
                .divide(market.getSpread().max(new BigDecimal("0.001")), 4, RoundingMode.HALF_UP)
                .min(BigDecimal.TEN);
        BigDecimal spreadComp = SPREAD_WEIGHT.multiply(spreadInverse);

        BigDecimal competitionComp = COMPETITION_WEIGHT.multiply(
                BigDecimal.ONE.subtract(market.getCompetitionLevel()));

        BigDecimal liquidityComp = LIQUIDITY_WEIGHT.multiply(market.getLiquidityScore());

        BigDecimal riskComp = RISK_WEIGHT.multiply(
                market.getVolatility().multiply(BigDecimal.TEN));

        BigDecimal total = rewardComp
                .add(spreadComp)
                .add(competitionComp)
                .add(liquidityComp)
                .subtract(riskComp)
                .setScale(4, RoundingMode.HALF_UP);

        return MarketScore.builder()
                .marketId(market.getMarketId())
                .marketName(market.getName())
                .totalScore(total)
                .rewardComponent(rewardComp.setScale(4, RoundingMode.HALF_UP))
                .spreadComponent(spreadComp.setScale(4, RoundingMode.HALF_UP))
                .competitionComponent(competitionComp.setScale(4, RoundingMode.HALF_UP))
                .liquidityComponent(liquidityComp.setScale(4, RoundingMode.HALF_UP))
                .riskComponent(riskComp.setScale(4, RoundingMode.HALF_UP))
                .build();
    }
}
