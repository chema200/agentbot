package com.agentbot.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;

@Entity
@Table(name = "pnl")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PnlRecord {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private BigDecimal realized;
    private BigDecimal unrealized;
    private BigDecimal daily;
    private LocalDate recordDate;
}
