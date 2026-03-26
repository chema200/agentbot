package com.agentbot.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class InventoryDto {
    private BigDecimal yesExposure;
    private BigDecimal noExposure;
    private BigDecimal netExposure;
}
