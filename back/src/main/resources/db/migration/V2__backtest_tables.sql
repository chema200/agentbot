CREATE TABLE backtest_runs (
    id BIGSERIAL PRIMARY KEY,
    run_id VARCHAR(64) NOT NULL UNIQUE,
    seed BIGINT NOT NULL,
    stress_profile VARCHAR(50) NOT NULL DEFAULT 'BASELINE',
    cycles INT NOT NULL DEFAULT 0,
    simulated_duration_sec INT NOT NULL DEFAULT 0,
    total_pnl NUMERIC(19,4) DEFAULT 0,
    trading_pnl NUMERIC(19,4) DEFAULT 0,
    reward_pnl NUMERIC(19,4) DEFAULT 0,
    total_fills INT DEFAULT 0,
    toxic_fills INT DEFAULT 0,
    total_fees NUMERIC(19,4) DEFAULT 0,
    max_exposure NUMERIC(19,4) DEFAULT 0,
    max_drawdown NUMERIC(19,4) DEFAULT 0,
    final_inventory_net NUMERIC(19,4) DEFAULT 0,
    avg_profit_per_fill NUMERIC(19,4) DEFAULT 0,
    adverse_selection_rate NUMERIC(8,4) DEFAULT 0,
    win_rate NUMERIC(8,4) DEFAULT 0,
    active_markets INT DEFAULT 0,
    config_json TEXT,
    elapsed_ms BIGINT DEFAULT 0,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);

CREATE TABLE monte_carlo_runs (
    id BIGSERIAL PRIMARY KEY,
    mc_run_id VARCHAR(64) NOT NULL UNIQUE,
    num_seeds INT NOT NULL,
    stress_profile VARCHAR(50) NOT NULL DEFAULT 'BASELINE',
    cycles_per_run INT NOT NULL DEFAULT 0,
    avg_pnl NUMERIC(19,4) DEFAULT 0,
    median_pnl NUMERIC(19,4) DEFAULT 0,
    std_pnl NUMERIC(19,4) DEFAULT 0,
    min_pnl NUMERIC(19,4) DEFAULT 0,
    max_pnl NUMERIC(19,4) DEFAULT 0,
    win_rate NUMERIC(8,4) DEFAULT 0,
    avg_drawdown NUMERIC(19,4) DEFAULT 0,
    max_drawdown NUMERIC(19,4) DEFAULT 0,
    avg_fills NUMERIC(10,2) DEFAULT 0,
    avg_toxic_fills NUMERIC(10,2) DEFAULT 0,
    sharpe_ratio NUMERIC(10,4) DEFAULT 0,
    elapsed_ms BIGINT DEFAULT 0,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);

CREATE INDEX idx_backtest_runs_run_id ON backtest_runs(run_id);
CREATE INDEX idx_backtest_runs_stress ON backtest_runs(stress_profile);
CREATE INDEX idx_backtest_runs_created ON backtest_runs(created_at);
CREATE INDEX idx_mc_runs_mc_run_id ON monte_carlo_runs(mc_run_id);
CREATE INDEX idx_mc_runs_created ON monte_carlo_runs(created_at);
