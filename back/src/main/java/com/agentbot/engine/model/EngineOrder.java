package com.agentbot.engine.model;

import lombok.Builder;
import lombok.Data;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Data
@Builder
public class EngineOrder {
    @Builder.Default
    private String orderId = UUID.randomUUID().toString().substring(0, 8);

    private String marketId;
    private String marketName;
    private Side side;
    private BigDecimal price;
    private BigDecimal originalSize;
    private BigDecimal remainingSize;
    private BigDecimal filledSize;
    private Status status;
    private Instant createdAt;
    private Instant updatedAt;

    public enum Side { BUY, SELL }
    public enum Status { OPEN, PARTIALLY_FILLED, FILLED, CANCELLED }

    public boolean isActive() {
        return status == Status.OPEN || status == Status.PARTIALLY_FILLED;
    }

    public void fill(BigDecimal qty) {
        this.filledSize = this.filledSize.add(qty);
        this.remainingSize = this.remainingSize.subtract(qty);
        this.updatedAt = Instant.now();
        if (this.remainingSize.compareTo(BigDecimal.ZERO) <= 0) {
            this.remainingSize = BigDecimal.ZERO;
            this.status = Status.FILLED;
        } else {
            this.status = Status.PARTIALLY_FILLED;
        }
    }

    public void cancel() {
        this.status = Status.CANCELLED;
        this.updatedAt = Instant.now();
    }
}
