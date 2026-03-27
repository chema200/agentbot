package com.agentbot.model;

import lombok.*;
import java.math.BigDecimal;
import java.time.Instant;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ShadowMarketDto {
    private String tokenId;
    private String question;
    private String outcome;
    private BigDecimal liveBestBid;
    private BigDecimal liveBestAsk;
    private BigDecimal liveMid;
    private BigDecimal liveSpread;
    private long tradeCount;
    private Instant lastUpdate;
    private String regime;
}
