package com.agentbot.polymarket.model;

import lombok.Data;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

@Data
public class LiveMarketState {
    private final String conditionId;
    private final String tokenId;
    private final String question;
    private final String outcome;

    private final AtomicReference<BigDecimal> bestBid = new AtomicReference<>(BigDecimal.ZERO);
    private final AtomicReference<BigDecimal> bestAsk = new AtomicReference<>(BigDecimal.ONE);
    private final AtomicReference<BigDecimal> lastTradePrice = new AtomicReference<>(BigDecimal.ZERO);
    private final AtomicReference<BigDecimal> lastTradeSize = new AtomicReference<>(BigDecimal.ZERO);
    private final AtomicReference<Instant> lastUpdateTime = new AtomicReference<>(Instant.now());
    private volatile long tradeCount = 0;

    private final AtomicReference<BigDecimal> prevMid = new AtomicReference<>(BigDecimal.ZERO);
    private final AtomicReference<BigDecimal> prevSpread = new AtomicReference<>(BigDecimal.ZERO);
    private volatile Regime regime = Regime.CALM;

    public enum Regime { CALM, NORMAL, VOLATILE, CRISIS }

    public BigDecimal getMidPrice() {
        BigDecimal bid = bestBid.get();
        BigDecimal ask = bestAsk.get();
        if (bid.compareTo(BigDecimal.ZERO) <= 0 || ask.compareTo(BigDecimal.ZERO) <= 0) return BigDecimal.ZERO;
        return bid.add(ask).divide(BigDecimal.TWO, 4, RoundingMode.HALF_UP);
    }

    public BigDecimal getSpread() {
        return bestAsk.get().subtract(bestBid.get());
    }

    public void updateBbo(BigDecimal bid, BigDecimal ask) {
        BigDecimal oldMid = getMidPrice();
        BigDecimal oldSpread = getSpread();

        if (bid != null && bid.compareTo(BigDecimal.ZERO) > 0) bestBid.set(bid);
        if (ask != null && ask.compareTo(BigDecimal.ZERO) > 0) bestAsk.set(ask);
        lastUpdateTime.set(Instant.now());

        if (oldMid.compareTo(BigDecimal.ZERO) > 0) {
            prevMid.set(oldMid);
            prevSpread.set(oldSpread);
            detectRegime();
        }
    }

    public void recordTrade(BigDecimal price, BigDecimal size) {
        lastTradePrice.set(price);
        lastTradeSize.set(size);
        lastUpdateTime.set(Instant.now());
        tradeCount++;
    }

    private void detectRegime() {
        BigDecimal mid = getMidPrice();
        BigDecimal oldMid = prevMid.get();
        BigDecimal spread = getSpread();
        BigDecimal oldSpread = prevSpread.get();

        if (mid.compareTo(BigDecimal.ZERO) <= 0 || oldMid.compareTo(BigDecimal.ZERO) <= 0) return;

        BigDecimal midMove = mid.subtract(oldMid).abs();
        BigDecimal midMovePct = midMove.divide(oldMid.max(new BigDecimal("0.001")), 6, RoundingMode.HALF_UP);

        BigDecimal spreadWiden = BigDecimal.ZERO;
        if (oldSpread.compareTo(BigDecimal.ZERO) > 0) {
            spreadWiden = spread.subtract(oldSpread).divide(oldSpread.max(new BigDecimal("0.001")), 6, RoundingMode.HALF_UP);
        }

        if (midMovePct.compareTo(new BigDecimal("0.10")) > 0 || spreadWiden.compareTo(new BigDecimal("3.0")) > 0) {
            regime = Regime.CRISIS;
        } else if (midMovePct.compareTo(new BigDecimal("0.04")) > 0 || spreadWiden.compareTo(new BigDecimal("1.5")) > 0) {
            regime = Regime.VOLATILE;
        } else if (midMovePct.compareTo(new BigDecimal("0.01")) > 0 || spreadWiden.compareTo(new BigDecimal("0.5")) > 0) {
            regime = Regime.NORMAL;
        } else {
            regime = Regime.CALM;
        }
    }
}
