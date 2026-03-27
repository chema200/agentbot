"use client";

import { useState, useEffect, useCallback } from "react";
import { api, ShadowStatus, ShadowMarket, ShadowOrder, ShadowFillItem } from "@/lib/api";

export default function ShadowPanel() {
  const [status, setStatus] = useState<ShadowStatus | null>(null);
  const [markets, setMarkets] = useState<ShadowMarket[]>([]);
  const [orders, setOrders] = useState<ShadowOrder[]>([]);
  const [fills, setFills] = useState<ShadowFillItem[]>([]);
  const [loading, setLoading] = useState(false);
  const [tab, setTab] = useState<"overview" | "markets" | "orders" | "fills">("overview");

  const refresh = useCallback(async () => {
    try {
      const s = await api.shadowStatus();
      setStatus(s);
      if (s.status === "RUNNING") {
        const [m, o, f] = await Promise.all([
          api.shadowMarkets(),
          api.shadowOrders(),
          api.shadowFills(),
        ]);
        setMarkets(m);
        setOrders(o);
        setFills(f);
      }
    } catch { /* ignore when shadow not available */ }
  }, []);

  useEffect(() => {
    refresh();
    const interval = setInterval(refresh, 3000);
    return () => clearInterval(interval);
  }, [refresh]);

  const handleStart = async () => {
    setLoading(true);
    try { await api.shadowStart(); } catch (e) { console.error(e); }
    setLoading(false);
    setTimeout(refresh, 1000);
  };

  const handleStop = async () => {
    setLoading(true);
    try { await api.shadowStop(); } catch (e) { console.error(e); }
    setLoading(false);
    setTimeout(refresh, 500);
  };

  const isRunning = status?.status === "RUNNING";
  const m = status?.metrics ?? {};

  return (
    <div className="card">
      <div className="flex items-center justify-between mb-4">
        <h2 className="text-sm font-medium text-dark-400 uppercase tracking-wider">
          Shadow Mode &mdash; Live Polymarket
        </h2>
        <div className="flex items-center gap-2">
          <StatusDot connected={isRunning} />
          <span className="text-xs text-dark-400">{status?.status ?? "UNKNOWN"}</span>
        </div>
      </div>

      <div className="flex items-center gap-2 mb-4">
        <button
          onClick={handleStart}
          disabled={loading || isRunning}
          className="px-3 py-1 rounded text-xs font-medium bg-accent-green text-dark-950 hover:brightness-110 disabled:opacity-40 transition-all"
        >
          Start Shadow
        </button>
        <button
          onClick={handleStop}
          disabled={loading || !isRunning}
          className="px-3 py-1 rounded text-xs font-medium bg-accent-red text-white hover:brightness-110 disabled:opacity-40 transition-all"
        >
          Stop
        </button>
        <div className="flex-1" />
        {status && (
          <div className="flex items-center gap-3 text-[10px] text-dark-500">
            <span>WS: <WsBadge ok={status.wsConnected} /></span>
            <span>Cycle: {status.cycleCount}</span>
            <span>Markets: {status.liveMarkets}</span>
          </div>
        )}
      </div>

      <div className="flex gap-2 mb-4">
        {(["overview", "markets", "orders", "fills"] as const).map((t) => (
          <button
            key={t}
            onClick={() => setTab(t)}
            className={`px-3 py-1 rounded text-xs font-medium transition-colors ${
              tab === t ? "bg-accent-blue text-white" : "bg-dark-800 text-dark-400 hover:text-dark-200"
            }`}
          >
            {t === "overview" ? "Overview" : t === "markets" ? "Live Markets" : t === "orders" ? "Hyp. Orders" : "Hyp. Fills"}
          </button>
        ))}
      </div>

      {tab === "overview" && <OverviewTab status={status} metrics={m} fills={fills} />}
      {tab === "markets" && <MarketsTab markets={markets} />}
      {tab === "orders" && <OrdersTab orders={orders} />}
      {tab === "fills" && <FillsTab fills={fills} />}
    </div>
  );
}

function OverviewTab({ status, metrics, fills }: { status: ShadowStatus | null; metrics: Record<string, any>; fills: ShadowFillItem[] }) {
  if (!status) return <div className="text-dark-500 text-sm text-center py-6">Shadow mode not active. Click Start to connect to Polymarket.</div>;

  return (
    <div className="space-y-4">
      <div className="grid grid-cols-2 sm:grid-cols-4 gap-3">
        <Metric label="Hyp. PnL" value={fmt(metrics.hypotheticalPnl)} color={metrics.hypotheticalPnl >= 0 ? "green" : "red"} />
        <Metric label="Fills" value={`${metrics.totalFills ?? 0} (${metrics.toxicFills ?? 0} toxic)`} />
        <Metric label="Fill Rate" value={pct(metrics.fillRate)} />
        <Metric label="Max DD" value={fmt(metrics.maxDrawdown)} color="red" />
      </div>
      <div className="grid grid-cols-2 sm:grid-cols-4 gap-3">
        <Metric label="Quotes" value={String(metrics.totalQuotes ?? 0)} />
        <Metric label="Toxic Rate" value={pct(metrics.toxicRate)} color={metrics.toxicRate > 0.3 ? "red" : undefined} />
        <Metric label="Fees" value={fmt(metrics.totalFees)} />
        <Metric label="Active Orders" value={String(status.activeOrders)} />
      </div>
      <div className="grid grid-cols-3 gap-3">
        <Metric label="Max YES Exp." value={fmt(metrics.maxYesExposure ?? 0)} />
        <Metric label="Max NO Exp." value={fmt(metrics.maxNoExposure ?? 0)} />
        <Metric label="Max Net Exp." value={fmt(metrics.maxNetExposure ?? 0)} color={metrics.maxNetExposure > 100 ? "red" : undefined} />
      </div>
    </div>
  );
}

function MarketsTab({ markets }: { markets: ShadowMarket[] }) {
  if (markets.length === 0) return <Empty />;
  return (
    <div className="overflow-x-auto">
      <table className="w-full text-xs">
        <thead>
          <tr className="border-b border-dark-700 text-dark-400">
            <th className="text-left py-1 px-2">Market</th>
            <th className="text-left py-1 px-2">Out</th>
            <th className="text-right py-1 px-2">Bid</th>
            <th className="text-right py-1 px-2">Ask</th>
            <th className="text-right py-1 px-2">Spread</th>
            <th className="text-right py-1 px-2">Trades</th>
            <th className="text-left py-1 px-2">Regime</th>
          </tr>
        </thead>
        <tbody>
          {markets.map((m) => (
            <tr key={m.tokenId} className="border-b border-dark-800 hover:bg-dark-800/50">
              <td className="py-1.5 px-2 max-w-[240px] truncate">{m.question}</td>
              <td className="py-1.5 px-2 font-mono text-dark-400">{m.outcome}</td>
              <td className="py-1.5 px-2 text-right font-mono text-accent-green">{m.liveBestBid.toFixed(2)}</td>
              <td className="py-1.5 px-2 text-right font-mono text-accent-red">{m.liveBestAsk.toFixed(2)}</td>
              <td className="py-1.5 px-2 text-right font-mono">{m.liveSpread.toFixed(3)}</td>
              <td className="py-1.5 px-2 text-right font-mono text-dark-400">{m.tradeCount}</td>
              <td className="py-1.5 px-2">
                <span className={`text-[10px] px-1.5 py-0.5 rounded font-medium ${
                  m.regime === "CALM" ? "bg-green-900/40 text-green-400"
                  : m.regime === "NORMAL" ? "bg-blue-900/40 text-blue-400"
                  : m.regime === "VOLATILE" ? "bg-yellow-900/40 text-yellow-400"
                  : "bg-red-900/40 text-red-400"}`}>{m.regime}</span>
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

function OrdersTab({ orders }: { orders: ShadowOrder[] }) {
  if (orders.length === 0) return <Empty msg="No active hypothetical orders" />;
  return (
    <div className="overflow-x-auto">
      <table className="w-full text-xs">
        <thead>
          <tr className="border-b border-dark-700 text-dark-400">
            <th className="text-left py-1 px-2">ID</th>
            <th className="text-left py-1 px-2">Market</th>
            <th className="text-left py-1 px-2">Side</th>
            <th className="text-right py-1 px-2">Price</th>
            <th className="text-right py-1 px-2">Size</th>
            <th className="text-right py-1 px-2">Cap%</th>
            <th className="text-right py-1 px-2">Edge</th>
            <th className="text-left py-1 px-2">Regime</th>
          </tr>
        </thead>
        <tbody>
          {orders.map((o) => {
            const capPct = o.capitalShare != null ? (o.capitalShare * 100) : 0;
            const capColor = capPct > 25 ? "text-accent-red" : capPct > 20 ? "text-yellow-400" : "text-dark-200";
            return (
              <tr key={o.orderId} className="border-b border-dark-800 hover:bg-dark-800/50">
                <td className="py-1.5 px-2 font-mono text-dark-500">{o.orderId}</td>
                <td className="py-1.5 px-2 max-w-[200px] truncate">{o.question} [{o.outcome}]</td>
                <td className="py-1.5 px-2">
                  <span className={o.side === "BUY" ? "badge-green" : "badge-red"}>{o.side}</span>
                </td>
                <td className="py-1.5 px-2 text-right font-mono">{o.price.toFixed(2)}</td>
                <td className="py-1.5 px-2 text-right font-mono">{o.size}</td>
                <td className={`py-1.5 px-2 text-right font-mono font-semibold ${capColor}`}>{capPct.toFixed(1)}%</td>
                <td className="py-1.5 px-2 text-right font-mono text-dark-400">{o.edgeScore?.toFixed(3)}</td>
                <td className="py-1.5 px-2">
                  <span className={`text-[10px] px-1.5 py-0.5 rounded font-medium ${
                    o.regime === "CALM" ? "bg-green-900/40 text-green-400"
                    : o.regime === "NORMAL" ? "bg-blue-900/40 text-blue-400"
                    : o.regime === "VOLATILE" ? "bg-yellow-900/40 text-yellow-400"
                    : "bg-red-900/40 text-red-400"}`}>{o.regime ?? "?"}</span>
                </td>
              </tr>
            );
          })}
        </tbody>
      </table>
    </div>
  );
}

function FillsTab({ fills }: { fills: ShadowFillItem[] }) {
  if (fills.length === 0) return <Empty msg="No hypothetical fills yet" />;
  return (
    <div className="overflow-x-auto max-h-72 overflow-y-auto">
      <table className="w-full text-xs">
        <thead className="sticky top-0 bg-dark-900">
          <tr className="border-b border-dark-700 text-dark-400">
            <th className="text-left py-1 px-2">ID</th>
            <th className="text-left py-1 px-2">Market</th>
            <th className="text-left py-1 px-2">Side</th>
            <th className="text-right py-1 px-2">Price</th>
            <th className="text-right py-1 px-2">Slip</th>
            <th className="text-right py-1 px-2">PnL</th>
            <th className="text-right py-1 px-2">Toxic</th>
          </tr>
        </thead>
        <tbody>
          {[...fills].reverse().map((f) => (
            <tr key={f.fillId} className="border-b border-dark-800 hover:bg-dark-800/50">
              <td className="py-1.5 px-2 font-mono text-dark-500">{f.fillId}</td>
              <td className="py-1.5 px-2 max-w-[200px] truncate">{f.question} [{f.outcome}]</td>
              <td className="py-1.5 px-2">
                <span className={f.side === "BUY" ? "badge-green" : "badge-red"}>{f.side}</span>
              </td>
              <td className="py-1.5 px-2 text-right font-mono">{f.fillPrice.toFixed(2)}</td>
              <td className="py-1.5 px-2 text-right font-mono">{f.slippage.toFixed(4)}</td>
              <td className={`py-1.5 px-2 text-right font-mono ${f.estimatedPnl >= 0 ? "text-accent-green" : "text-accent-red"}`}>
                {f.estimatedPnl.toFixed(4)}
              </td>
              <td className="py-1.5 px-2 text-right">
                {f.toxic && <span className="text-[10px] px-1.5 py-0.5 rounded bg-accent-red/20 text-accent-red">TOXIC</span>}
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

function StatusDot({ connected }: { connected: boolean }) {
  return <div className={`w-2 h-2 rounded-full ${connected ? "bg-accent-green animate-pulse" : "bg-dark-600"}`} />;
}

function WsBadge({ ok }: { ok: boolean }) {
  return <span className={`font-medium ${ok ? "text-accent-green" : "text-accent-red"}`}>{ok ? "OK" : "OFF"}</span>;
}

function Metric({ label, value, color }: { label: string; value: string; color?: string }) {
  const cls = color === "green" ? "text-accent-green" : color === "red" ? "text-accent-red" : "text-dark-200";
  return (
    <div className="bg-dark-800 rounded-lg px-3 py-2">
      <div className="text-[10px] text-dark-500 uppercase">{label}</div>
      <div className={`text-sm font-mono font-medium ${cls}`}>{value}</div>
    </div>
  );
}

function Empty({ msg }: { msg?: string }) {
  return <div className="text-dark-500 text-sm text-center py-6">{msg ?? "No data yet"}</div>;
}

function fmt(v: any): string { return v != null ? Number(v).toFixed(4) : "0.0000"; }
function pct(v: any): string { return v != null ? `${(Number(v) * 100).toFixed(1)}%` : "0.0%"; }
