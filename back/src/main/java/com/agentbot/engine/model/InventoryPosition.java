package com.agentbot.engine.model;

import lombok.Data;
import java.math.BigDecimal;

@Data
public class InventoryPosition {
    private final String marketId;
    private BigDecimal yesQuantity = BigDecimal.ZERO;
    private BigDecimal noQuantity = BigDecimal.ZERO;

    public InventoryPosition(String marketId) {
        this.marketId = marketId;
    }

    public BigDecimal getNetExposure() {
        return yesQuantity.subtract(noQuantity);
    }

    public BigDecimal getTotalExposure() {
        return yesQuantity.add(noQuantity);
    }

    public void addYes(BigDecimal qty) {
        this.yesQuantity = this.yesQuantity.add(qty);
    }

    public void addNo(BigDecimal qty) {
        this.noQuantity = this.noQuantity.add(qty);
    }
}
