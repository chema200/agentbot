package com.agentbot.engine;

import com.agentbot.engine.model.EngineOrder;
import com.agentbot.engine.model.EngineFill;
import com.agentbot.engine.model.InventoryPosition;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
public class InventoryManager {

    private final Map<String, InventoryPosition> positions = new ConcurrentHashMap<>();

    public void processFill(EngineFill fill) {
        InventoryPosition pos = positions.computeIfAbsent(
                fill.getMarketId(), InventoryPosition::new);

        if (fill.getSide() == EngineOrder.Side.BUY) {
            pos.addYes(fill.getSize());
        } else {
            pos.addNo(fill.getSize());
        }

        log.debug("Inventory update {}: YES={} NO={} NET={}",
                fill.getMarketId(), pos.getYesQuantity(), pos.getNoQuantity(), pos.getNetExposure());
    }

    public InventoryPosition getPosition(String marketId) {
        return positions.computeIfAbsent(marketId, InventoryPosition::new);
    }

    public Collection<InventoryPosition> getAllPositions() {
        return positions.values();
    }

    public BigDecimal getTotalYesExposure() {
        return positions.values().stream()
                .map(InventoryPosition::getYesQuantity)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    public BigDecimal getTotalNoExposure() {
        return positions.values().stream()
                .map(InventoryPosition::getNoQuantity)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    public BigDecimal getGlobalNetExposure() {
        return getTotalYesExposure().subtract(getTotalNoExposure()).abs();
    }

    public void reset() {
        positions.clear();
    }
}
