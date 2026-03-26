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
  market: string;
  side: "BUY" | "SELL";
  price: number;
  size: number;
  status: "OPEN" | "CANCELLED" | "FILLED";
  createdAt: string;
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
}

export interface Market {
  marketId: string;
  name: string;
  bestBid: number;
  bestAsk: number;
  spread: number;
  volume: number;
  liquidityScore: number;
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

export const api = {
  getStatus: () => fetchApi<BotStatus>("/api/status"),
  getOrders: () => fetchApi<Order[]>("/api/orders"),
  getFills: () => fetchApi<Fill[]>("/api/fills"),
  getInventory: () => fetchApi<Inventory>("/api/inventory"),
  getPnl: () => fetchApi<PnL>("/api/pnl"),
  getMarkets: () => fetchApi<Market[]>("/api/markets"),
  startEngine: () => postApi<{ status: string }>("/api/engine/start"),
  pauseEngine: () => postApi<{ status: string }>("/api/engine/pause"),
  stopEngine: () => postApi<{ status: string }>("/api/engine/stop"),
};
