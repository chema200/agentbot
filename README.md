# AgentBot - Polymarket Market-Making Engine

A full-stack system for simulated and shadow-mode market-making on Polymarket prediction markets. Includes a real trading engine (simulated markets), a shadow engine (live Polymarket data via CLOB API + WebSocket), risk controls, structured logging, and a React dashboard.

## Architecture

| Layer       | Technology                          |
|-------------|-------------------------------------|
| Backend     | Java 21, Spring Boot 3.3, Gradle    |
| Frontend    | Next.js 14, React 18, TypeScript    |
| Styling     | TailwindCSS, dark fintech theme     |
| Database    | PostgreSQL 16                       |
| Migrations  | Flyway                              |
| Infra       | Docker Compose                      |

## Project Structure

```
agentbot/
├── back/
│   ├── src/main/java/com/agentbot/
│   │   ├── controller/         # REST controllers (Dashboard, Shadow, Debug)
│   │   ├── engine/             # Real trading engine (simulated markets)
│   │   │   ├── TradingEngine        # Main engine loop with risk controls
│   │   │   ├── TradingConfig        # Externalized risk parameters
│   │   │   ├── MarketRankingEngine  # Market selection & scoring
│   │   │   ├── StrategyEngine       # Quote placement strategy
│   │   │   ├── RiskManager          # Global risk evaluation
│   │   │   ├── InventoryManager     # YES/NO exposure tracking
│   │   │   ├── PnLService           # Realized/unrealized PnL
│   │   │   ├── QuoteSupervisor      # Order lifecycle & fills
│   │   │   └── RewardEngine         # Liquidity reward simulation
│   │   ├── shadow/             # Shadow trading engine (live Polymarket)
│   │   │   ├── ShadowTradingEngine  # Shadow engine with full risk controls
│   │   │   ├── ShadowFillModel      # Hypothetical fill evaluation
│   │   │   └── ShadowComparisonMetrics # Unified metrics accumulator
│   │   ├── polymarket/         # Polymarket API integration
│   │   │   ├── PolymarketMarketDiscoveryService  # Market discovery via Gamma API
│   │   │   ├── PolymarketMarketDataClient        # WebSocket for live BBO
│   │   │   └── ShadowConfig                      # Shadow engine configuration
│   │   ├── service/            # Dashboard data service
│   │   ├── model/              # JPA entities & DTOs
│   │   ├── repository/         # Spring Data repositories
│   │   └── config/             # CORS, app config
│   ├── src/main/resources/
│   │   ├── application.yml
│   │   ├── application-dev.yml    # Full config with risk params
│   │   ├── logback-spring.xml     # Structured file logging
│   │   └── db/migration/         # Flyway SQL migrations
│   └── logs/                      # Runtime logs (auto-generated)
│       ├── trading-engine.log     # Real engine logs
│       ├── shadow-engine.log      # Shadow engine logs
│       ├── agentbot-review.log    # Combined review log
│       ├── app.log                # General application log
│       └── validation-summary.txt # Session summary (via API)
├── front/
│   └── src/
│       ├── app/                # Pages (dashboard, shadow panel)
│       ├── components/         # UI components (charts, tables, modals)
│       ├── hooks/              # Custom React hooks
│       └── lib/                # API client
├── docker-compose.yml
└── README.md
```

## Engines

### Real Trading Engine
Runs against simulated markets with configurable volatility regimes (CALM, NORMAL, VOLATILE, CRISIS). Features:
- Hard capital cap per market (`maxCapitalSharePerMarket`)
- Regime-based blocking (CRISIS never active, VOLATILE conditional)
- Cooldown after toxic fills
- Dynamic order sizing by regime, inventory, and edge confidence
- Structured `[REAL_SNAPSHOT]` logging every N cycles

### Shadow Trading Engine
Runs against **live Polymarket data** via CLOB REST API and WebSocket. Places hypothetical orders, evaluates fills against real BBO movements, and tracks PnL without risking capital.
- Same risk controls as real engine (cap, regime, cooldown)
- Edge calculation: `rawEdge = spread / (2 * mid)`, with regime penalty and clamp
- Price validation: rejects orders with `price <= 0.03` or `price >= 0.97`
- Extreme market filter: skips markets with `mid < 0.04` or `mid > 0.96`
- Structured logs: `[SHADOW_FILL]`, `[CANCEL]`, `[ORDER_REJECT]`, `[CYCLE_SUMMARY]`, `[REGIME_BLOCK]`, `[COOLDOWN_START/END]`, `[CAP_CLAMP]`, `[SIZE_DECISION]`, `[MARKET_DECISION]`

## Risk Controls

All configurable via `application-dev.yml`:

| Parameter | Default | Description |
|-----------|---------|-------------|
| `maxCapitalSharePerMarket` | 0.25 | Hard cap per market (25%) |
| `regimePenaltyCalm/Normal/Volatile/Crisis` | 1.0/0.8/0.35/0.0 | Edge multipliers by regime |
| `blockVolatileMarkets` | true | Block all VOLATILE markets |
| `cooldownCycles` | 15 | Cycles to pause after toxic fill |
| `minEdgeAfterPenalty` | 0.005 | Minimum penalized edge to quote |
| `edgeClampMin/Max` | 0.0/0.50 | Edge normalization bounds |
| `maxYes/No/NetExposure` | 200/200/150 | Inventory limits |
| `inventoryPenaltyK` | 0.5 | Inventory skew penalty factor |
| `minOrderSize/maxOrderSize` | 5/50 | Order size bounds |

## Quick Start

### Prerequisites
- Java 21 (JDK)
- Node.js 20+
- PostgreSQL 16

### Database Setup

```sql
CREATE DATABASE agentbot;
CREATE USER agentbot WITH PASSWORD 'agentbot';
GRANT ALL PRIVILEGES ON DATABASE agentbot TO agentbot;
```

### Backend

```bash
cd back
./gradlew bootRun
```

Backend starts on port `8080`.

### Frontend

```bash
cd front
npm install
npm run dev
```

Frontend starts on port `3000`.

### Docker Compose

```bash
docker compose up --build
```

## API Endpoints

### Dashboard
| Method | Path | Description |
|--------|------|-------------|
| GET | `/api/status` | Engine status |
| GET | `/api/orders` | Active orders |
| GET | `/api/orders/{id}` | Order detail modal data |
| GET | `/api/fills` | Executed fills |
| GET | `/api/inventory` | YES/NO exposure |
| GET | `/api/pnl` | Profit & Loss |
| GET | `/api/markets` | Market data with regime |

### Shadow Mode
| Method | Path | Description |
|--------|------|-------------|
| POST | `/api/shadow/start` | Start shadow engine |
| POST | `/api/shadow/stop` | Stop shadow engine |
| GET | `/api/shadow/status` | Shadow status + metrics |
| GET | `/api/shadow/markets` | Live market BBO data |
| GET | `/api/shadow/orders` | Active shadow orders |
| GET | `/api/shadow/fills` | Shadow fills (last 50) |
| GET | `/api/shadow/pnl` | Shadow PnL summary |

### Debug / Validation
| Method | Path | Description |
|--------|------|-------------|
| GET | `/api/debug/export-logs` | Export trading engine logs (TXT) |
| GET | `/api/shadow/debug/export-logs` | Export shadow engine logs (TXT) |
| GET | `/api/debug/validation-summary` | Full session summary (real + shadow) |

### Engine Control
| Method | Path | Description |
|--------|------|-------------|
| POST | `/api/engine/start` | Start real engine |
| POST | `/api/engine/pause` | Pause real engine |
| POST | `/api/engine/stop` | Stop real engine |

## Logging

Logs are written to `back/logs/` via Logback with file rotation:
- `trading-engine.log` - Real engine operations
- `shadow-engine.log` - Shadow engine operations
- `agentbot-review.log` - Combined review log (both engines)
- `app.log` - General application log

Key structured log tags:
- `[CYCLE_SUMMARY]` - Periodic snapshot with all metrics
- `[SHADOW_FILL]` / `[CANCEL]` - Individual fill/cancel events
- `[ORDER_REJECT]` - Rejected orders (extreme price, out of range)
- `[CAP_CLAMP]` / `[CAP_VIOLATION]` - Capital allocation events
- `[REGIME_BLOCK]` - Markets blocked by regime
- `[COOLDOWN_START/END]` - Toxic fill cooldown events
- `[REAL_SNAPSHOT]` - Real engine periodic snapshot

## Validation Workflow

1. Start both engines (real + shadow)
2. Let run for 20-30 minutes
3. Call `GET /api/debug/validation-summary` to get full session metrics
4. Copy logs from `logs/agentbot-review.log` or call export endpoints
5. Paste into external analysis tool for review

## Environment Variables

| Variable | Default | Description |
|----------|---------|-------------|
| DB_HOST | localhost | PostgreSQL host |
| DB_PORT | 5432 | PostgreSQL port |
| DB_NAME | agentbot | Database name |
| DB_USER | agentbot | Database user |
| DB_PASSWORD | agentbot | Database password |
| NEXT_PUBLIC_API_URL | http://localhost:8080 | Backend URL for frontend |
