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
public class PnlDto {
    private BigDecimal realized;
    private BigDecimal unrealized;
    private BigDecimal daily;
}
