package com.agentbot.model;

import lombok.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OrderDetailDto {

    private String orderId;
    private String marketId;
    private String marketName;
    private String side;
    private BigDecimal price;
    private BigDecimal originalSize;
    private BigDecimal remainingSize;
    private BigDecimal filledSize;
    private String status;
    private Instant createdAt;
    private Instant updatedAt;
    private long ageSeconds;
    private BigDecimal queueAhead;
    private int queuePosition;
    private Instant visibleAfter;
    private String lastActionReason;

    private List<FillDetail> fills;

    private MarketSnapshotDto marketSnapshot;

    private MarketSummaryDto marketSummary;

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class FillDetail {
        private String fillId;
        private String side;
        private BigDecimal fillPrice;
        private BigDecimal fillSize;
        private BigDecimal fee;
        private BigDecimal slippage;
        private BigDecimal midAtFill;
        private boolean toxicFlow;
        private Instant filledAt;
        private BigDecimal estimatedPnl;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class MarketSnapshotDto {
        private BigDecimal edgeScore;
        private BigDecimal rewardEfficiency;
        private BigDecimal competitionDensity;
        private BigDecimal volatilityPenalty;
        private BigDecimal capitalShare;
        private BigDecimal spread;
        private BigDecimal bestBid;
        private BigDecimal bestAsk;
        private BigDecimal mid;
        private String regime;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class MarketSummaryDto {
        private int totalFills;
        private BigDecimal tradingPnl;
        private BigDecimal rewardPnl;
        private BigDecimal netExposure;
        private int activeOrders;
    }
}
