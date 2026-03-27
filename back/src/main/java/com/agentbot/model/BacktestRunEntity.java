package com.agentbot.model;

import jakarta.persistence.*;
import lombok.*;
import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "backtest_runs")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BacktestRunEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "run_id", nullable = false, unique = true)
    private String runId;

    private long seed;

    @Column(name = "stress_profile")
    private String stressProfile;

    private int cycles;

    @Column(name = "simulated_duration_sec")
    private int simulatedDurationSec;

    @Column(name = "total_pnl")
    @Builder.Default
    private BigDecimal totalPnl = BigDecimal.ZERO;

    @Column(name = "trading_pnl")
    @Builder.Default
    private BigDecimal tradingPnl = BigDecimal.ZERO;

    @Column(name = "reward_pnl")
    @Builder.Default
    private BigDecimal rewardPnl = BigDecimal.ZERO;

    @Column(name = "total_fills")
    private int totalFills;

    @Column(name = "toxic_fills")
    private int toxicFills;

    @Column(name = "total_fees")
    @Builder.Default
    private BigDecimal totalFees = BigDecimal.ZERO;

    @Column(name = "max_exposure")
    @Builder.Default
    private BigDecimal maxExposure = BigDecimal.ZERO;

    @Column(name = "max_drawdown")
    @Builder.Default
    private BigDecimal maxDrawdown = BigDecimal.ZERO;

    @Column(name = "final_inventory_net")
    @Builder.Default
    private BigDecimal finalInventoryNet = BigDecimal.ZERO;

    @Column(name = "avg_profit_per_fill")
    @Builder.Default
    private BigDecimal avgProfitPerFill = BigDecimal.ZERO;

    @Column(name = "adverse_selection_rate")
    @Builder.Default
    private BigDecimal adverseSelectionRate = BigDecimal.ZERO;

    @Column(name = "win_rate")
    @Builder.Default
    private BigDecimal winRate = BigDecimal.ZERO;

    @Column(name = "active_markets")
    private int activeMarkets;

    @Column(name = "config_json", columnDefinition = "TEXT")
    private String configJson;

    @Column(name = "elapsed_ms")
    private long elapsedMs;

    @Column(name = "created_at")
    @Builder.Default
    private Instant createdAt = Instant.now();
}
