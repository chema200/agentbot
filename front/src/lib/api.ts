const API_BASE = process.env.NEXT_PUBLIC_API_URL || "http://localhost:8080";

async function fetchApi<T>(endpoint: string): Promise<T> {
  const res = await fetch(`${API_BASE}${endpoint}`, {
    cache: "no-store",
  });
  if (!res.ok) throw new Error(`API error: ${res.status}`);
  return res.json();
}

export interface Order {
  id: number;
  orderId: string;
  market: string;
  side: "BUY" | "SELL";
  price: number;
  size: number;
  status: "OPEN" | "CANCELLED" | "FILLED";
  createdAt: string;
}

export interface FillDetail {
  fillId: string;
  side: string;
  fillPrice: number;
  fillSize: number;
  fee: number;
  slippage: number;
  midAtFill: number;
  toxicFlow: boolean;
  filledAt: string;
  estimatedPnl: number;
}

export interface MarketSnapshotDetail {
  edgeScore: number;
  rewardEfficiency: number;
  competitionDensity: number;
  volatilityPenalty: number;
  capitalShare: number;
  spread: number;
  bestBid: number;
  bestAsk: number;
  mid: number;
  regime: string;
}

export interface MarketSummaryDetail {
  totalFills: number;
  tradingPnl: number;
  rewardPnl: number;
  netExposure: number;
  activeOrders: number;
}

export interface OrderDetail {
  orderId: string;
  marketId: string;
  marketName: string;
  side: string;
  price: number;
  originalSize: number;
  remainingSize: number;
  filledSize: number;
  status: string;
  createdAt: string;
  updatedAt: string;
  ageSeconds: number;
  queueAhead: number;
  queuePosition: number;
  visibleAfter: string | null;
  lastActionReason: string | null;
  fills: FillDetail[];
  marketSnapshot: MarketSnapshotDetail;
  marketSummary: MarketSummaryDetail;
}

export interface Fill {
  id: number;
  market: string;
  side: "BUY" | "SELL";
  price: number;
  size: number;
  fee: number;
  filledAt: string;
}

export interface Inventory {
  yesExposure: number;
  noExposure: number;
  netExposure: number;
}

export interface PnL {
  realized: number;
  unrealized: number;
  daily: number;
  tradingPnl: number;
  rewardPnl: number;
  totalPnl: number;
  fees: number;
}

export interface Market {
  marketId: string;
  name: string;
  bestBid: number;
  bestAsk: number;
  spread: number;
  volume: number;
  liquidityScore: number;
  edgeScore: number | null;
  rewardEfficiency: number | null;
  competitionDensity: number | null;
  volatilityPenalty: number | null;
  selected: boolean;
  regime: string | null;
}

export interface BotStatus {
  botStatus: "RUNNING" | "PAUSED" | "STOPPED" | "STARTING" | "ERROR";
  connection: "OK" | "ERROR";
  uptime: number;
}

async function postApi<T>(endpoint: string): Promise<T> {
  const res = await fetch(`${API_BASE}${endpoint}`, {
    method: "POST",
    cache: "no-store",
  });
  if (!res.ok) throw new Error(`API error: ${res.status}`);
  return res.json();
}

export interface BacktestResult {
  runId: string;
  seed: number;
  stressProfile: string;
  cycles: number;
  simulatedDurationSec: number;
  totalPnl: number;
  tradingPnl: number;
  rewardPnl: number;
  totalFills: number;
  toxicFills: number;
  totalFees: number;
  maxExposure: number;
  maxDrawdown: number;
  finalInventoryNet: number;
  avgProfitPerFill: number;
  adverseSelectionRate: number;
  winRate: number;
  activeMarkets: number;
  elapsedMs: number;
  createdAt: string;
}

export interface MonteCarloResult {
  mcRunId: string;
  numSeeds: number;
  stressProfile: string;
  cyclesPerRun: number;
  avgPnl: number;
  medianPnl: number;
  stdPnl: number;
  minPnl: number;
  maxPnl: number;
  winRate: number;
  avgDrawdown: number;
  maxDrawdown: number;
  avgFills: number;
  avgToxicFills: number;
  sharpeRatio: number;
  elapsedMs: number;
  createdAt: string;
  individualRuns?: BacktestResult[];
}

export const api = {
  getStatus: () => fetchApi<BotStatus>("/api/status"),
  getOrders: () => fetchApi<Order[]>("/api/orders"),
  getOrderDetail: (orderId: string) => fetchApi<OrderDetail>(`/api/orders/${orderId}`),
  getFills: () => fetchApi<Fill[]>("/api/fills"),
  getInventory: () => fetchApi<Inventory>("/api/inventory"),
  getPnl: () => fetchApi<PnL>("/api/pnl"),
  getMarkets: () => fetchApi<Market[]>("/api/markets"),
  startEngine: () => postApi<{ status: string }>("/api/engine/start"),
  pauseEngine: () => postApi<{ status: string }>("/api/engine/pause"),
  stopEngine: () => postApi<{ status: string }>("/api/engine/stop"),
  runBacktest: (cycles: number, seed: number, profile: string) =>
    postApi<BacktestResult>(`/api/backtest/run?cycles=${cycles}&seed=${seed}&profile=${profile}`),
  runMonteCarlo: (cycles: number, seeds: number, profile: string) =>
    postApi<MonteCarloResult>(`/api/backtest/monte-carlo?cycles=${cycles}&seeds=${seeds}&profile=${profile}`),
  getBacktestRuns: () => fetchApi<BacktestResult[]>("/api/backtest/runs"),
  getMcRuns: () => fetchApi<MonteCarloResult[]>("/api/backtest/monte-carlo/runs"),
  getProfiles: () => fetchApi<string[]>("/api/backtest/profiles"),
  getComparison: () => fetchApi<Record<string, any>>("/api/backtest/comparison"),
};
