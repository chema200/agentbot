package com.agentbot.engine.model;

import lombok.Builder;
import lombok.Data;
import java.math.BigDecimal;
import java.time.Instant;

@Data
@Builder
public class EngineFill {
    private String fillId;
    private String orderId;
    private String marketId;
    private String marketName;
    private EngineOrder.Side side;
    private BigDecimal price;
    private BigDecimal size;
    private BigDecimal fee;
    private Instant filledAt;
}
