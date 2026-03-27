# AgentBot - Polymarket Market-Making Engine

A full-stack system for simulated and shadow-mode market-making on Polymarket prediction markets. Includes a real trading engine (simulated markets), a shadow engine (live Polymarket data via CLOB API + WebSocket), per-market quality guard, risk controls, structured logging, and a React dashboard.

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
│   │   │   ├── ShadowTradingEngine       # Shadow engine with full risk controls
│   │   │   ├── ShadowFillModel           # Hypothetical fill evaluation
│   │   │   ├── ShadowComparisonMetrics   # Unified metrics accumulator
│   │   │   └── ShadowMarketGuardService  # Per-market quality guard
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
│   │   ├── application-dev.yml    # Full config with risk + guard params
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
│       ├── app/                # Pages (dashboard with 3 tabs)
│       ├── components/         # UI components
│       │   ├── SimulationTab      # Simulation engine dashboard
│       │   ├── ShadowTab          # Shadow engine dashboard + guard view
│       │   ├── RealTradingTab     # Real trading (disabled, prepared)
│       │   ├── DataTable          # Reusable table with pagination/search/sort
│       │   ├── InfoTooltip        # Metric glossary tooltips
│       │   └── HelpSection        # Collapsible help sections
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
- **Market Quality Guard** (see below)
- Structured logs: `[SHADOW_FILL]`, `[CANCEL]`, `[ORDER_REJECT]`, `[SHADOW_CYCLE_SUMMARY]`, `[REGIME_BLOCK]`, `[COOLDOWN_START/END]`, `[CAP_CLAMP]`, `[SIZE_DECISION]`, `[MARKET_DECISION]`

## Market Quality Guard (ShadowMarketGuardService)

The guard is the adaptive layer that prevents the bot from bleeding money on bad markets. It tracks rolling statistics per market and degrades or disables markets dynamically.

### Problem Solved

Without the guard, the shadow engine would:
- Let a single bad market concentrate >50% of fills and drag total PnL negative
- Keep quoting markets with high stale cancel rates and near-zero fill rates
- Not react to consecutive losses or deteriorating execution quality

### Guard States

| State | Behavior |
|-------|----------|
| `ACTIVE` | Normal quoting, no restrictions |
| `SOFT_COOLDOWN` | Market paused for ~5 min (100 cycles at 3s/cycle) |
| `HARD_COOLDOWN` | Market paused for ~10 min (200 cycles at 3s/cycle) |
| `DISABLED_SESSION` | Market disabled for the rest of the session |

### Guard Rules

| # | Condition | Action |
|---|-----------|--------|
| 1 | `rolling_pnl_5m < -0.05` | SOFT_COOLDOWN |
| 2 | `fills_share > 35%` AND `session_pnl < 0` | HARD_COOLDOWN |
| 3 | `consecutive_negative_fills >= 6` | HARD_COOLDOWN |
| 4 | `stale_cancel_rate > 80%` AND `fill_rate < 5%` | Edge penalty +0.30 |
| 5 | `avg_slippage > 0.002` (after 5+ fills) | Edge penalty +0.20 |
| 6 | One-sided flow >80% (after 10+ fills) | Edge penalty +0.15 |
| 7 | `session_pnl < 5x threshold` AND `fills >= 10` | DISABLED_SESSION |

### Per-Market Metrics Tracked

- `sessionFills`, `fillsBuy`, `fillsSell` - fill counts and side breakdown
- `sessionPnl` - cumulative PnL for this market
- `rollingPnl1m`, `rollingPnl5m` - time-windowed PnL
- `avgSlippage` - average absolute slippage per fill
- `staleCancelRate` - stale cancels / (cancels + fills)
- `fillRate` - fills / quote attempts
- `consecutiveNegativeFills` - streak of losing fills
- `quoteAttempts`, `cancelCount`, `staleCancelCount`
- `guardPenalty` - accumulated edge penalty (0.0 to 0.9)
- `sessionToxicFills`, `transitionCount`

### Guard Flow

1. Every cycle, `tick()` checks cooldown expirations and reactivates markets
2. On every fill, `recordFill()` updates rolling stats and evaluates guard rules
3. On every cancel, `recordCancel()` tracks stale cancels
4. On every quote attempt, `recordQuoteAttempt()` tracks participation
5. Before quoting, `shouldQuote(tokenId)` blocks markets in cooldown/disabled state
6. `getGuardPenalty(tokenId)` reduces effective edge for degraded markets
7. Every N cycles, `logQualitySnapshots()` emits `[MARKET_QUALITY_SNAPSHOT]` for all active markets

### Concentration Control (HHI)

The guard calculates a Herfindahl-Hirschman Index (HHI) to measure fill concentration:
- HHI = sum of (market_fills / total_fills)^2
- HHI = 1.0 means all fills in one market (maximum concentration)
- HHI < 0.10 means fills are well distributed
- Exposed in the guard summary and dashboard

### Guard Configuration

All guard parameters are in `application-dev.yml`:

| Parameter | Default | Description |
|-----------|---------|-------------|
| `guard-pnl5m-threshold` | -0.05 | Rolling PnL 5m to trigger soft cooldown |
| `guard-fills-share-threshold` | 0.35 | Max fill share before hard cooldown (if losing) |
| `guard-max-consecutive-negative` | 6 | Consecutive losses to trigger hard cooldown |
| `guard-stale-cancel-rate-threshold` | 0.80 | Stale cancel rate to trigger edge penalty |
| `guard-avg-slippage-threshold` | 0.002 | Avg slippage to trigger edge penalty |
| `guard-soft-cooldown-cycles` | 100 | Soft cooldown duration (~5 min) |
| `guard-hard-cooldown-cycles` | 200 | Hard cooldown duration (~10 min) |
| `guard-quality-snapshot-interval` | 15 | Cycles between quality snapshots |

## Risk Controls

All configurable via `application-dev.yml`:

| Parameter | Default | Description |
|-----------|---------|-------------|
| `maxCapitalSharePerMarket` | 0.25 | Hard cap per market (25%) |
| `regimePenaltyCalm/Normal/Volatile/Crisis` | 1.0/0.8/0.35/0.0 | Edge multipliers by regime |
| `blockVolatileMarkets` | false | Block all VOLATILE markets |
| `cooldownCycles` | 15 | Cycles to pause after toxic fill |
| `minEdgeAfterPenalty` | 0.005 | Minimum penalized edge to quote |
| `edgeClampMin/Max` | 0.0/1.0 | Edge normalization bounds |
| `maxYes/No/NetExposure` | 200/200/100 | Inventory limits |
| `inventoryPenaltyK` | 0.5 | Inventory skew penalty factor |
| `minOrderSize/maxOrderSize` | 5/25 | Order size bounds |

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

Frontend starts on port `3000`. Dashboard at http://localhost:3000/dashboard

### Docker Compose

```bash
docker compose up --build
```

## Dashboard - 3 Modos

El dashboard tiene 3 pestanas principales accesibles desde el header:

### Tab: Simulacion

Motor interno de simulacion. Laboratorio de estrategia.

| Seccion | Que muestra |
|---------|------------|
| Engine Status | Estado del motor simulado (RUNNING/PAUSED/STOPPED), ciclos |
| Controls | Start/Pause/Stop del motor de simulacion |
| Inventory | Exposicion YES/NO/Net del motor simulado |
| PnL | Trading PnL, Reward PnL, Total PnL, Fees (simulacion) |
| Quick Stats | Ordenes activas, fills, mercados, fill rate |
| Orders Table | Ordenes del motor simulado con sorting y click para detalle |
| Markets - Edge | Tabla de mercados con edge score, regime, spread, etc. |
| Validation | Backtests y Monte Carlo para validar la estrategia |

Endpoints: `/api/status`, `/api/orders`, `/api/fills`, `/api/inventory`, `/api/pnl`, `/api/markets`

### Tab: Shadow Polymarket

Conexion a mercados REALES de Polymarket. Modo principal de trabajo. Sub-tabs:

| Sub-tab | Que muestra |
|---------|------------|
| Resumen | Status, WS, ciclos, mercados vivos, ordenes activas |
| Live Markets | Tabla de mercados reales con bid/ask/mid/spread/regime, paginacion y busqueda |
| Hyp. Orders | Ordenes hipoteticas activas con edge, cap%, regime |
| Hyp. Fills | Fills hipoteticos con precio, slippage, PnL, toxic flag |
| Guard | **Semaforo de calidad por mercado**, HHI, concentracion, guard status, penalties |
| Logs | Logs estructurados del shadow engine con descarga TXT |

Summary cards: Hyp. PnL, Fills, Fill Rate, Max Drawdown, Fees, Active Orders, Max YES/NO/Net Exposure, Toxic Rate, Quotes.

Endpoints: `/api/shadow/status`, `/api/shadow/markets`, `/api/shadow/orders`, `/api/shadow/fills`, `/api/shadow/guard/summary`, `/api/shadow/guard/markets`, `/api/shadow/debug/export-logs`

### Tab: Real Trading

Preparado pero DESACTIVADO. Muestra configuracion de risk limits, wallet, kill switch.
Ninguna operacion real se ejecuta. Se activara cuando Shadow muestre resultados consistentes.

### Metricas principales

| Metrica | Significado |
|---------|------------|
| Edge | Diferencia entre precio justo estimado y precio de mercado |
| Reward Efficiency | Recompensa por liquidez / capital desplegado |
| Competition Density | % de competidores activos en el libro |
| Spread | Diferencia bid-ask |
| Volatility Penalty | Factor de penalizacion por volatilidad |
| Regime | CALM / NORMAL / VOLATILE / CRISIS |
| Hyp. PnL | PnL hipotetico (shadow = habria ganado con dinero real) |
| Toxic Fill | Fill con movimiento adverso inmediato (seleccion adversa) |
| Fill Rate | % de ordenes que reciben ejecucion |
| Max Drawdown | Mayor caida desde un pico de PnL |
| Max Net Exposure | Mayor desequilibrio neto YES vs NO |
| Capital Share | % del presupuesto asignado a un mercado (hard cap 25%) |
| Guard Status | Estado del market quality guard (ACTIVE/SOFT/HARD/DISABLED) |
| HHI | Indice de concentracion Herfindahl-Hirschman (0=distribuido, 1=concentrado) |
| Fill Share | % de fills de la sesion que corresponden a este mercado |
| Guard Penalty | Penalizacion acumulada de edge por calidad del mercado |

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
| GET | `/api/shadow/guard/summary` | Guard summary (HHI, cooldowns, PnL saved) |
| GET | `/api/shadow/guard/markets` | Per-market guard data |

### Debug / Validation
| Method | Path | Description |
|--------|------|-------------|
| GET | `/api/debug/export-logs` | Export trading engine logs (TXT) |
| GET | `/api/shadow/debug/export-logs` | Export shadow engine logs (TXT) |
| GET | `/api/debug/validation-summary` | Full session summary (real + shadow) |
| GET | `/api/debug/structured-logs` | Extract structured log events |

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

### Structured Log Tags

| Tag | Engine | Description |
|-----|--------|-------------|
| `[SHADOW_CYCLE_SUMMARY]` | Shadow | Periodic snapshot with all metrics + guard counts |
| `[SHADOW_FILL]` | Shadow | Individual fill event |
| `[CANCEL]` | Shadow | Order cancel (stale timeout) |
| `[ORDER_REJECT]` | Shadow | Rejected order (price out of range) |
| `[CAP_CLAMP]` / `[CAP_VIOLATION]` | Both | Capital allocation events |
| `[REGIME_BLOCK]` | Both | Market blocked by regime |
| `[COOLDOWN_START/END]` | Both | Toxic fill cooldown events |
| `[EDGE_CLAMP]` | Both | Edge normalization |
| `[MARKET_DECISION]` | Both | Per-market quoting decision |
| `[SIZE_DECISION]` | Both | Order sizing breakdown |
| `[MARKET_GUARD_STATE]` | Shadow | Guard state transition (ACTIVE -> COOLDOWN) |
| `[MARKET_GUARD_PENALTY]` | Shadow | Guard edge penalty applied |
| `[MARKET_QUALITY_SNAPSHOT]` | Shadow | Per-market quality metrics |
| `[MARKET_DISABLED]` | Shadow | Market disabled for session |
| `[REAL_SNAPSHOT]` | Real | Real engine periodic snapshot |
| `[METRICS_INCONSISTENCY]` | Shadow | Internal consistency warning |

## Validation Workflow

1. Start ambos motores (simulacion + shadow) desde sus respectivas tabs
2. Dejar correr 15-20 minutos
3. Ir a Shadow > Guard para ver que mercados estan en cooldown y por que
4. En Shadow > Logs, descargar TXT con logs estructurados
5. O llamar `GET /api/debug/validation-summary` para resumen completo
6. Verificar que HHI < 0.30 (concentracion razonable)
7. Verificar que no hay mercados con fill_share > 35% y PnL negativo
8. Copiar logs de `logs/agentbot-review.log` para revision externa
9. Pasar al analista externo para diagnostico

### Run Summary (end of session)

When the shadow engine stops, it prints a comprehensive block:
- FINAL: fills, toxic_fills, trading_pnl, fees, exposures, max drawdown
- GUARD: markets_tracked, soft/hard/disabled counts, HHI, top fills market, estimated PnL saved
- TOP_PNL_MARKETS: top 3 markets by PnL
- TOP_TOXIC_MARKETS: top 3 markets by toxic fill count

## Environment Variables

| Variable | Default | Description |
|----------|---------|-------------|
| DB_HOST | localhost | PostgreSQL host |
| DB_PORT | 5432 | PostgreSQL port |
| DB_NAME | agentbot | Database name |
| DB_USER | agentbot | Database user |
| DB_PASSWORD | agentbot | Database password |
| NEXT_PUBLIC_API_URL | http://localhost:8080 | Backend URL for frontend |
