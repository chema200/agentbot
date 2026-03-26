package com.agentbot.engine;

import com.agentbot.engine.model.SimulatedMarket;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.concurrent.ThreadLocalRandom;

@Slf4j
@Component
public class MarketDataEngine {

    private static final BigDecimal TICK_SIZE = new BigDecimal("0.01");
    private static final BigDecimal MIN_PRICE = new BigDecimal("0.02");
    private static final BigDecimal MAX_PRICE = new BigDecimal("0.98");

    public void tick(SimulatedMarket market) {
        ThreadLocalRandom rng = ThreadLocalRandom.current();

        BigDecimal drift = BigDecimal.valueOf(rng.nextGaussian())
                .multiply(market.getVolatility())
                .multiply(TICK_SIZE)
                .setScale(4, RoundingMode.HALF_UP);

        BigDecimal newMid = market.getMidPrice().add(drift)
                .max(MIN_PRICE).min(MAX_PRICE)
                .setScale(2, RoundingMode.HALF_UP);

        BigDecimal halfSpread = randomizeSpread(market, rng);

        BigDecimal newBid = newMid.subtract(halfSpread).max(MIN_PRICE).setScale(2, RoundingMode.HALF_UP);
        BigDecimal newAsk = newMid.add(halfSpread).min(MAX_PRICE).setScale(2, RoundingMode.HALF_UP);

        if (newBid.compareTo(newAsk) >= 0) {
            newAsk = newBid.add(TICK_SIZE);
        }

        BigDecimal tradeVolume = BigDecimal.valueOf(rng.nextInt(50, 500));

        market.setPreviousMid(market.getMidPrice());
        market.setMidPrice(newMid);
        market.setBestBid(newBid);
        market.setBestAsk(newAsk);
        market.setSpread(newAsk.subtract(newBid));
        market.setVolume(market.getVolume().add(tradeVolume));
        market.setLastUpdate(Instant.now());

        market.updateMomentum();

        jitterVolatility(market, rng);
        jitterRewardAndCompetition(market, rng);
    }

    private BigDecimal randomizeSpread(SimulatedMarket market, ThreadLocalRandom rng) {
        BigDecimal baseHalf = market.getSpread().divide(BigDecimal.TWO, 4, RoundingMode.HALF_UP);
        BigDecimal jitter = BigDecimal.valueOf(rng.nextDouble(-0.005, 0.005));
        BigDecimal result = baseHalf.add(jitter).max(TICK_SIZE.divide(BigDecimal.TWO, 4, RoundingMode.HALF_UP));
        return result.setScale(4, RoundingMode.HALF_UP);
    }

    private void jitterVolatility(SimulatedMarket market, ThreadLocalRandom rng) {
        if (rng.nextInt(10) == 0) {
            BigDecimal change = BigDecimal.valueOf(rng.nextDouble(-0.005, 0.005));
            BigDecimal newVol = market.getVolatility().add(change)
                    .max(new BigDecimal("0.005"))
                    .min(new BigDecimal("0.20"));
            market.setVolatility(newVol.setScale(4, RoundingMode.HALF_UP));
        }
    }

    private void jitterRewardAndCompetition(SimulatedMarket market, ThreadLocalRandom rng) {
        if (rng.nextInt(20) == 0) {
            BigDecimal rewardChange = BigDecimal.valueOf(rng.nextDouble(-0.3, 0.3));
            market.setRewardScore(market.getRewardScore().add(rewardChange)
                    .max(BigDecimal.ONE).min(BigDecimal.TEN)
                    .setScale(2, RoundingMode.HALF_UP));
        }
        if (rng.nextInt(15) == 0) {
            BigDecimal compChange = BigDecimal.valueOf(rng.nextDouble(-0.1, 0.1));
            market.setCompetitionLevel(market.getCompetitionLevel().add(compChange)
                    .max(BigDecimal.ZERO).min(BigDecimal.ONE)
                    .setScale(2, RoundingMode.HALF_UP));
        }
    }
}
