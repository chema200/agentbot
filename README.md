# AgentBot - Polymarket Trading Dashboard

A full-stack local application for monitoring and controlling a Polymarket trading bot.

## Tech Stack

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
├── back/                    # Spring Boot backend
│   ├── src/main/java/com/agentbot/
│   │   ├── controller/      # REST controllers
│   │   ├── service/         # Business logic & mock data
│   │   ├── model/           # JPA entities & DTOs
│   │   ├── repository/      # Spring Data repositories
│   │   └── config/          # CORS, app config
│   └── src/main/resources/
│       ├── application.yml
│       └── db/migration/    # Flyway SQL migrations
├── front/                   # Next.js frontend
│   └── src/
│       ├── app/             # Pages (dashboard)
│       ├── components/      # UI components
│       ├── hooks/           # Custom React hooks
│       └── lib/             # API client
├── docker-compose.yml
└── README.md
```

## Quick Start (Docker Compose)

Run everything with a single command:

```bash
docker compose up --build
```

Services:

| Service   | URL                    |
|-----------|------------------------|
| Frontend  | http://localhost:3000   |
| Backend   | http://localhost:8080   |
| Postgres  | localhost:5432          |

## Local Development (without Docker)

### Prerequisites

- Java 21 (JDK)
- Node.js 20+
- PostgreSQL 16 (running locally)

### Database Setup

Create the database:

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

The backend starts on port `8080`. Flyway runs migrations automatically.

### Frontend

```bash
cd front
npm install
npm run dev
```

The frontend starts on port `3000`.

## API Endpoints

All endpoints return mock data at this stage.

| Method | Path            | Description          |
|--------|-----------------|----------------------|
| GET    | /api/status     | Bot status           |
| GET    | /api/orders     | Trading orders       |
| GET    | /api/fills      | Executed fills       |
| GET    | /api/inventory  | YES/NO exposure      |
| GET    | /api/pnl        | Profit & Loss        |
| GET    | /api/markets    | Market data          |

## Mock Data

The backend returns realistic hardcoded data through `MockDataService`. This includes:

- **Orders**: Buy/sell orders across prediction markets with various statuses
- **Fills**: Executed trade fills with fees
- **Inventory**: YES/NO/Net exposure amounts
- **PnL**: Realized, unrealized, and daily profit/loss
- **Markets**: Market names, bid/ask prices, spread, volume, and liquidity scores

Data is not persisted to the database at this stage. The database schema exists and is managed by Flyway for future use.

## Environment Variables

| Variable         | Default     | Description              |
|------------------|-------------|--------------------------|
| DB_HOST          | localhost   | PostgreSQL host          |
| DB_PORT          | 5432        | PostgreSQL port          |
| DB_NAME          | agentbot    | Database name            |
| DB_USER          | agentbot    | Database user            |
| DB_PASSWORD      | agentbot    | Database password        |
| NEXT_PUBLIC_API_URL | http://localhost:8080 | Backend URL for frontend |

## Architecture Notes

- Frontend fetches data from backend API with polling (every 5 seconds)
- Backend serves mock data through a service layer (easy to swap with real implementations)
- Database schema is ready for real data storage via Flyway migrations
- CORS is configured to allow frontend-backend communication
- Docker Compose orchestrates all services in an isolated network
