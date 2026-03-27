package com.agentbot.model;

import lombok.*;
import java.math.BigDecimal;
import java.time.Instant;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ShadowOrderDto {
    private String orderId;
    private String tokenId;
    private String question;
    private String outcome;
    private String side;
    private BigDecimal price;
    private BigDecimal size;
    private String status;
    private Instant createdAt;
    private BigDecimal liveBestBid;
    private BigDecimal liveBestAsk;
    private BigDecimal liveMid;
    private BigDecimal edgeScore;
    private BigDecimal capitalShare;
    private String regime;
}
