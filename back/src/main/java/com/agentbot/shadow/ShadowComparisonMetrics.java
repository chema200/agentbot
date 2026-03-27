package com.agentbot.shadow;

import lombok.Data;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

@Slf4j
@Component
public class ShadowComparisonMetrics {

    private final AtomicInteger totalQuotesCounted = new AtomicInteger(0);
    private final AtomicInteger totalHypotheticalFills = new AtomicInteger(0);
    private final AtomicInteger toxicFills = new AtomicInteger(0);
    private final AtomicReference<BigDecimal> totalHypotheticalPnl = new AtomicReference<>(BigDecimal.ZERO);
    private final AtomicReference<BigDecimal> totalFees = new AtomicReference<>(BigDecimal.ZERO);
    private final AtomicReference<BigDecimal> peakPnl = new AtomicReference<>(BigDecimal.ZERO);
    private final AtomicReference<BigDecimal> maxDrawdown = new AtomicReference<>(BigDecimal.ZERO);
    private final AtomicReference<BigDecimal> maxYesExposure = new AtomicReference<>(BigDecimal.ZERO);
    private final AtomicReference<BigDecimal> maxNoExposure = new AtomicReference<>(BigDecimal.ZERO);
    private final AtomicReference<BigDecimal> maxNetExposure = new AtomicReference<>(BigDecimal.ZERO);

    @Getter private BigDecimal yesExposureCurrent = BigDecimal.ZERO;
    @Getter private BigDecimal noExposureCurrent = BigDecimal.ZERO;

    private final Map<String, TokenMetrics> perTokenMetrics = new ConcurrentHashMap<>();

    public void recordQuote() {
        totalQuotesCounted.incrementAndGet();
    }

    public synchronized void recordFill(ShadowFill fill) {
        totalHypotheticalFills.incrementAndGet();
        if (fill.isWouldHaveBeenToxic()) toxicFills.incrementAndGet();

        totalHypotheticalPnl.updateAndGet(v -> v.add(fill.getEstimatedPnl()));
        totalFees.updateAndGet(v -> v.add(fill.getFee()));

        BigDecimal currentPnl = totalHypotheticalPnl.get();
        peakPnl.updateAndGet(v -> v.max(currentPnl));
        BigDecimal dd = peakPnl.get().subtract(currentPnl);
        maxDrawdown.updateAndGet(v -> v.max(dd));

        BigDecimal qty = fill.getFillSize();
        if ("BUY".equals(fill.getSide())) {
            yesExposureCurrent = yesExposureCurrent.add(qty);
        } else {
            noExposureCurrent = noExposureCurrent.add(qty);
        }
        maxYesExposure.updateAndGet(v -> v.max(yesExposureCurrent));
        maxNoExposure.updateAndGet(v -> v.max(noExposureCurrent));
        BigDecimal net = yesExposureCurrent.subtract(noExposureCurrent).abs();
        maxNetExposure.updateAndGet(v -> v.max(net));

        perTokenMetrics.computeIfAbsent(fill.getTokenId(), k -> new TokenMetrics())
                .recordFill(fill);
    }

    public int getTotalFills() { return totalHypotheticalFills.get(); }
    public int getToxicFills() { return toxicFills.get(); }
    public BigDecimal getTotalPnl() { return totalHypotheticalPnl.get(); }
    public BigDecimal getTotalFees() { return totalFees.get(); }
    public BigDecimal getMaxDrawdown() { return maxDrawdown.get(); }
    public BigDecimal getMaxYes() { return maxYesExposure.get(); }
    public BigDecimal getMaxNo() { return maxNoExposure.get(); }
    public BigDecimal getMaxNet() { return maxNetExposure.get(); }
    public Map<String, TokenMetrics> getPerTokenMetrics() { return perTokenMetrics; }

    public List<Map.Entry<String, TokenMetrics>> getTopPnlMarkets(int n) {
        return perTokenMetrics.entrySet().stream()
                .sorted((a, b) -> b.getValue().getPnl().compareTo(a.getValue().getPnl()))
                .limit(n)
                .collect(Collectors.toList());
    }

    public List<Map.Entry<String, TokenMetrics>> getTopToxicMarkets(int n) {
        return perTokenMetrics.entrySet().stream()
                .filter(e -> e.getValue().getToxicFills() > 0)
                .sorted((a, b) -> Integer.compare(b.getValue().getToxicFills(), a.getValue().getToxicFills()))
                .limit(n)
                .collect(Collectors.toList());
    }

    public String getTopMarketId() {
        return perTokenMetrics.entrySet().stream()
                .max(Comparator.comparing(e -> e.getValue().getPnl()))
                .map(Map.Entry::getKey)
                .orElse("none");
    }

    public BigDecimal getTopMarketPnl() {
        return perTokenMetrics.values().stream()
                .map(TokenMetrics::getPnl)
                .max(Comparator.naturalOrder())
                .orElse(BigDecimal.ZERO);
    }

    public String getWorstMarketId() {
        return perTokenMetrics.entrySet().stream()
                .max(Comparator.comparingInt(e -> e.getValue().getToxicFills()))
                .map(Map.Entry::getKey)
                .orElse("none");
    }

    public double getWorstMarketToxicRate() {
        return perTokenMetrics.values().stream()
                .filter(m -> m.getFills() > 0)
                .mapToDouble(m -> (double) m.getToxicFills() / m.getFills())
                .max()
                .orElse(0.0);
    }

    public Map<String, Object> getSummary() {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("totalQuotes", totalQuotesCounted.get());
        summary.put("totalFills", totalHypotheticalFills.get());
        summary.put("toxicFills", toxicFills.get());
        summary.put("hypotheticalPnl", totalHypotheticalPnl.get().setScale(4, RoundingMode.HALF_UP));
        summary.put("totalFees", totalFees.get().setScale(6, RoundingMode.HALF_UP));
        summary.put("maxDrawdown", maxDrawdown.get().setScale(4, RoundingMode.HALF_UP));

        double fillRate = totalQuotesCounted.get() > 0
                ? (double) totalHypotheticalFills.get() / totalQuotesCounted.get() : 0.0;
        summary.put("fillRate", BigDecimal.valueOf(fillRate).setScale(4, RoundingMode.HALF_UP));

        double toxicRate = totalHypotheticalFills.get() > 0
                ? (double) toxicFills.get() / totalHypotheticalFills.get() : 0.0;
        summary.put("toxicRate", BigDecimal.valueOf(toxicRate).setScale(4, RoundingMode.HALF_UP));
        summary.put("maxYesExposure", maxYesExposure.get().setScale(2, RoundingMode.HALF_UP));
        summary.put("maxNoExposure", maxNoExposure.get().setScale(2, RoundingMode.HALF_UP));
        summary.put("maxNetExposure", maxNetExposure.get().setScale(2, RoundingMode.HALF_UP));
        return summary;
    }

    public void reset() {
        totalQuotesCounted.set(0);
        totalHypotheticalFills.set(0);
        toxicFills.set(0);
        totalHypotheticalPnl.set(BigDecimal.ZERO);
        totalFees.set(BigDecimal.ZERO);
        peakPnl.set(BigDecimal.ZERO);
        maxDrawdown.set(BigDecimal.ZERO);
        maxYesExposure.set(BigDecimal.ZERO);
        maxNoExposure.set(BigDecimal.ZERO);
        maxNetExposure.set(BigDecimal.ZERO);
        yesExposureCurrent = BigDecimal.ZERO;
        noExposureCurrent = BigDecimal.ZERO;
        perTokenMetrics.clear();
    }

    @Data
    public static class TokenMetrics {
        private int fills = 0;
        private int toxicFills = 0;
        private BigDecimal pnl = BigDecimal.ZERO;
        private BigDecimal totalSlippage = BigDecimal.ZERO;

        public void recordFill(ShadowFill fill) {
            fills++;
            if (fill.isWouldHaveBeenToxic()) toxicFills++;
            pnl = pnl.add(fill.getEstimatedPnl());
            totalSlippage = totalSlippage.add(fill.getSlippage());
        }
    }
}
