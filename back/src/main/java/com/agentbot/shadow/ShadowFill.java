package com.agentbot.shadow;

import lombok.Builder;
import lombok.Data;
import java.math.BigDecimal;
import java.time.Instant;

@Data
@Builder
public class ShadowFill {
    private String fillId;
    private String orderId;
    private String tokenId;
    private String marketQuestion;
    private String outcome;
    private String side;
    private BigDecimal fillPrice;
    private BigDecimal fillSize;
    private BigDecimal fee;
    private BigDecimal slippage;
    private BigDecimal midAtFill;
    private BigDecimal liveBidAtFill;
    private BigDecimal liveAskAtFill;
    private boolean wouldHaveBeenToxic;
    private Instant filledAt;
    private BigDecimal estimatedPnl;
}
