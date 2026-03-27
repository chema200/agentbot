package com.agentbot.model;

import jakarta.persistence.*;
import lombok.*;
import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "monte_carlo_runs")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MonteCarloRunEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "mc_run_id", nullable = false, unique = true)
    private String mcRunId;

    @Column(name = "num_seeds")
    private int numSeeds;

    @Column(name = "stress_profile")
    private String stressProfile;

    @Column(name = "cycles_per_run")
    private int cyclesPerRun;

    @Column(name = "avg_pnl") @Builder.Default private BigDecimal avgPnl = BigDecimal.ZERO;
    @Column(name = "median_pnl") @Builder.Default private BigDecimal medianPnl = BigDecimal.ZERO;
    @Column(name = "std_pnl") @Builder.Default private BigDecimal stdPnl = BigDecimal.ZERO;
    @Column(name = "min_pnl") @Builder.Default private BigDecimal minPnl = BigDecimal.ZERO;
    @Column(name = "max_pnl") @Builder.Default private BigDecimal maxPnl = BigDecimal.ZERO;
    @Column(name = "win_rate") @Builder.Default private BigDecimal winRate = BigDecimal.ZERO;
    @Column(name = "avg_drawdown") @Builder.Default private BigDecimal avgDrawdown = BigDecimal.ZERO;
    @Column(name = "max_drawdown") @Builder.Default private BigDecimal maxDrawdown = BigDecimal.ZERO;
    @Column(name = "avg_fills") @Builder.Default private BigDecimal avgFills = BigDecimal.ZERO;
    @Column(name = "avg_toxic_fills") @Builder.Default private BigDecimal avgToxicFills = BigDecimal.ZERO;
    @Column(name = "sharpe_ratio") @Builder.Default private BigDecimal sharpeRatio = BigDecimal.ZERO;

    @Column(name = "elapsed_ms")
    private long elapsedMs;

    @Column(name = "created_at")
    @Builder.Default
    private Instant createdAt = Instant.now();
}
