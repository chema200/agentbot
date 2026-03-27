package com.agentbot.shadow;

import lombok.Builder;
import lombok.Data;
import java.math.BigDecimal;
import java.time.Instant;

@Data
@Builder
public class ShadowOrder {
    private String orderId;
    private String tokenId;
    private String marketQuestion;
    private String outcome;
    private String side;
    private BigDecimal price;
    private BigDecimal size;
    private String status;
    private Instant createdAt;
    private Instant cancelledAt;
    
    private BigDecimal liveBestBid;
    private BigDecimal liveBestAsk;
    private BigDecimal liveMid;
    private BigDecimal edgeScore;
    private BigDecimal capitalShare;
    private String regime;
    
    public boolean isActive() {
        return "OPEN".equals(status);
    }
}
