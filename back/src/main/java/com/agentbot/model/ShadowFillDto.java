package com.agentbot.model;

import lombok.*;
import java.math.BigDecimal;
import java.time.Instant;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ShadowFillDto {
    private String fillId;
    private String tokenId;
    private String question;
    private String outcome;
    private String side;
    private BigDecimal fillPrice;
    private BigDecimal fillSize;
    private BigDecimal fee;
    private BigDecimal slippage;
    private BigDecimal midAtFill;
    private BigDecimal liveBidAtFill;
    private BigDecimal liveAskAtFill;
    private boolean toxic;
    private BigDecimal estimatedPnl;
    private Instant filledAt;
}
