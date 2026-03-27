CREATE TABLE shadow_snapshots (
    id BIGSERIAL PRIMARY KEY,
    shadow_run_id VARCHAR(64) NOT NULL,
    cycle BIGINT NOT NULL DEFAULT 0,
    token_id VARCHAR(256) NOT NULL,
    market_question TEXT,
    outcome VARCHAR(50),
    live_best_bid NUMERIC(19,6) DEFAULT 0,
    live_best_ask NUMERIC(19,6) DEFAULT 0,
    live_mid NUMERIC(19,6) DEFAULT 0,
    hypothetical_bid NUMERIC(19,6),
    hypothetical_ask NUMERIC(19,6),
    hypothetical_fill BOOLEAN DEFAULT FALSE,
    hypothetical_fill_price NUMERIC(19,6),
    hypothetical_slippage NUMERIC(19,6),
    hypothetical_fee NUMERIC(19,6),
    hypothetical_pnl NUMERIC(19,6),
    was_toxic BOOLEAN DEFAULT FALSE,
    edge_score NUMERIC(10,4) DEFAULT 0,
    capital_share NUMERIC(10,4) DEFAULT 0,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);

CREATE TABLE shadow_runs (
    id BIGSERIAL PRIMARY KEY,
    shadow_run_id VARCHAR(64) NOT NULL UNIQUE,
    started_at TIMESTAMP WITH TIME ZONE,
    stopped_at TIMESTAMP WITH TIME ZONE,
    total_cycles BIGINT DEFAULT 0,
    total_markets INT DEFAULT 0,
    total_fills INT DEFAULT 0,
    toxic_fills INT DEFAULT 0,
    hypothetical_pnl NUMERIC(19,6) DEFAULT 0,
    total_fees NUMERIC(19,6) DEFAULT 0,
    max_drawdown NUMERIC(19,6) DEFAULT 0,
    fill_rate NUMERIC(8,4) DEFAULT 0,
    ws_connected BOOLEAN DEFAULT FALSE,
    status VARCHAR(20) DEFAULT 'STOPPED'
);

CREATE INDEX idx_shadow_snapshots_run ON shadow_snapshots(shadow_run_id);
CREATE INDEX idx_shadow_snapshots_token ON shadow_snapshots(token_id);
CREATE INDEX idx_shadow_snapshots_created ON shadow_snapshots(created_at);
CREATE INDEX idx_shadow_runs_created ON shadow_runs(started_at);
