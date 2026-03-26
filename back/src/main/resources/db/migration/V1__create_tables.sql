CREATE TABLE orders (
    id BIGSERIAL PRIMARY KEY,
    market VARCHAR(255) NOT NULL,
    side VARCHAR(10) NOT NULL,
    price NUMERIC(19,4) NOT NULL,
    size NUMERIC(19,4) NOT NULL,
    status VARCHAR(20) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);

CREATE TABLE fills (
    id BIGSERIAL PRIMARY KEY,
    market VARCHAR(255) NOT NULL,
    side VARCHAR(10) NOT NULL,
    price NUMERIC(19,4) NOT NULL,
    size NUMERIC(19,4) NOT NULL,
    fee NUMERIC(19,4) DEFAULT 0,
    filled_at TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);

CREATE TABLE pnl (
    id BIGSERIAL PRIMARY KEY,
    realized NUMERIC(19,4) DEFAULT 0,
    unrealized NUMERIC(19,4) DEFAULT 0,
    daily NUMERIC(19,4) DEFAULT 0,
    record_date DATE DEFAULT CURRENT_DATE
);

CREATE INDEX idx_orders_status ON orders(status);
CREATE INDEX idx_orders_market ON orders(market);
CREATE INDEX idx_fills_market ON fills(market);
CREATE INDEX idx_pnl_date ON pnl(record_date);
