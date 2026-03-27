"use client";

import { useState, useEffect, useCallback } from "react";
import { api, ShadowStatus, ShadowMarket, ShadowOrder, ShadowFillItem } from "@/lib/api";
import DataTable from "./DataTable";
import InfoTooltip from "./InfoTooltip";
import HelpSection from "./HelpSection";

type SubTab = "overview" | "markets" | "orders" | "fills" | "guard" | "logs";

interface GuardMarketData {
  tokenId: string;
  status: string;
  sessionFills: number;
  sessionPnl: number;
  fillsShare: number;
  rollingPnl5m: number;
  avgSlippage: number;
  staleCancelRate: number;
  fillRate: number;
  consecutiveNegFills: number;
  guardPenalty: number;
  fillsBuy: number;
  fillsSell: number;
  cancelCount: number;
  staleCancelCount: number;
  quoteAttempts: number;
  sessionToxicFills: number;
  transitionCount: number;
  lastReason: string;
}

interface GuardSummary {
  marketsTracked: number;
  softCooldownCount: number;
  hardCooldownCount: number;
  disabledSessionCount: number;
  totalTransitions: number;
  hhi?: number;
  topFillsMarket?: string;
  topFillsShare?: number;
  topFillsPnl?: number;
  estimatedPnlSaved: number;
}

export default function ShadowTab() {
  const [status, setStatus] = useState<ShadowStatus | null>(null);
  const [markets, setMarkets] = useState<ShadowMarket[]>([]);
  const [orders, setOrders] = useState<ShadowOrder[]>([]);
  const [fills, setFills] = useState<ShadowFillItem[]>([]);
  const [guardMarkets, setGuardMarkets] = useState<GuardMarketData[]>([]);
  const [guardSummary, setGuardSummary] = useState<GuardSummary | null>(null);
  const [loading, setLoading] = useState(false);
  const [tab, setTab] = useState<SubTab>("overview");
  const [logText, setLogText] = useState("");

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
        try {
          const base = process.env.NEXT_PUBLIC_API_URL || "http://localhost:8080";
          const [gm, gs] = await Promise.all([
            fetch(`${base}/api/shadow/guard/markets`).then(r => r.json()),
            fetch(`${base}/api/shadow/guard/summary`).then(r => r.json()),
          ]);
          setGuardMarkets(gm);
          setGuardSummary(gs);
        } catch { /* guard not available */ }
      }
    } catch { /* shadow not available */ }
  }, []);

  useEffect(() => {
    refresh();
    const interval = setInterval(refresh, 3000);
    return () => clearInterval(interval);
  }, [refresh]);

  useEffect(() => {
    if (tab !== "logs") return;
    const fetchLogs = async () => {
      try {
        const res = await fetch(`${process.env.NEXT_PUBLIC_API_URL || "http://localhost:8080"}/api/shadow/debug/export-logs`);
        setLogText(await res.text());
      } catch { setLogText("Error cargando logs"); }
    };
    fetchLogs();
    const iv = setInterval(fetchLogs, 10000);
    return () => clearInterval(iv);
  }, [tab]);

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
    <div className="space-y-5">
      <HelpSection
        title="Como leer esta pantalla"
        items={[
          "Shadow Polymarket conecta a mercados REALES de Polymarket via WebSocket y API REST.",
          "Las ordenes son HIPOTETICAS: se colocan virtualmente pero nunca se ejecutan con dinero real.",
          "Los fills se evaluan comparando precios de las ordenes hipoteticas con el BBO real en vivo.",
          "Hyp. PnL muestra cuanto habrias ganado/perdido si las ordenes fueran reales.",
          "Usa esta vista como paso previo a trading real: si aqui no es rentable, en real tampoco lo sera.",
        ]}
      />

      {/* Controls */}
      <div className="flex items-center gap-3">
        <button onClick={handleStart} disabled={loading || isRunning}
          className="px-4 py-2 rounded-lg text-sm font-medium bg-accent-green/15 text-accent-green hover:bg-accent-green/25 disabled:opacity-40 transition-all">
          Start Shadow
        </button>
        <button onClick={handleStop} disabled={loading || !isRunning}
          className="px-4 py-2 rounded-lg text-sm font-medium bg-accent-red/15 text-accent-red hover:bg-accent-red/25 disabled:opacity-40 transition-all">
          Stop
        </button>
        <div className="flex-1" />
        {status && (
          <div className="flex items-center gap-4 text-xs text-dark-400">
            <span>WS: <span className={status.wsConnected ? "text-accent-green font-medium" : "text-accent-red font-medium"}>{status.wsConnected ? "OK" : "OFF"}</span></span>
            <span>Cycle: <span className="text-dark-200 font-mono">{status.cycleCount}</span></span>
            <span>Status: <span className={`font-medium ${isRunning ? "text-accent-green" : "text-dark-400"}`}>{status.status}</span></span>
          </div>
        )}
      </div>

      {/* Summary cards */}
      <div className="grid grid-cols-2 sm:grid-cols-4 lg:grid-cols-6 gap-3">
        <MetricCard label="Hyp. PnL" value={fmt(m.hypotheticalPnl)} color={Number(m.hypotheticalPnl ?? 0) >= 0 ? "green" : "red"} tooltip="Hyp PnL" />
        <MetricCard label="Fills" value={`${m.totalFills ?? 0}`} sub={`${m.toxicFills ?? 0} toxic`} tooltip="Fill Rate" />
        <MetricCard label="Fill Rate" value={pct(m.fillRate)} tooltip="Fill Rate" />
        <MetricCard label="Max Drawdown" value={fmt(m.maxDrawdown)} color="red" tooltip="Max Drawdown" />
        <MetricCard label="Fees" value={fmt(m.totalFees)} />
        <MetricCard label="Active Orders" value={String(status?.activeOrders ?? 0)} />
      </div>

      <div className="grid grid-cols-3 sm:grid-cols-5 gap-3">
        <MetricCard label="Quotes" value={String(m.totalQuotes ?? 0)} />
        <MetricCard label="Toxic Rate" value={pct(m.toxicRate)} color={Number(m.toxicRate ?? 0) > 0.3 ? "red" : undefined} tooltip="Toxic Fill" />
        <MetricCard label="Max YES Exp." value={fmt(m.maxYesExposure)} tooltip="Max Net Exposure" />
        <MetricCard label="Max NO Exp." value={fmt(m.maxNoExposure)} />
        <MetricCard label="Max Net Exp." value={fmt(m.maxNetExposure)} color={Number(m.maxNetExposure ?? 0) > 100 ? "red" : undefined} tooltip="Max Net Exposure" />
      </div>

      {/* Sub-tabs */}
      <div className="flex gap-2 border-b border-dark-800 pb-2">
        {([
          ["overview", "Resumen"],
          ["markets", "Live Markets"],
          ["orders", "Hyp. Orders"],
          ["fills", "Hyp. Fills"],
          ["guard", "Guard"],
          ["logs", "Logs"],
        ] as [SubTab, string][]).map(([id, label]) => (
          <button
            key={id}
            onClick={() => setTab(id)}
            className={`px-3 py-1.5 rounded-t text-xs font-medium transition-colors ${
              tab === id ? "bg-dark-800 text-dark-100 border-b-2 border-accent-blue" : "text-dark-500 hover:text-dark-300"
            }`}
          >
            {label}
            {id === "fills" && fills.length > 0 && <span className="ml-1.5 px-1.5 py-0.5 rounded-full bg-dark-700 text-[10px]">{fills.length}</span>}
            {id === "markets" && markets.length > 0 && <span className="ml-1.5 px-1.5 py-0.5 rounded-full bg-dark-700 text-[10px]">{markets.length}</span>}
            {id === "guard" && guardSummary && (guardSummary.softCooldownCount + guardSummary.hardCooldownCount + guardSummary.disabledSessionCount) > 0 && (
              <span className="ml-1.5 px-1.5 py-0.5 rounded-full bg-accent-red/20 text-accent-red text-[10px]">
                {guardSummary.softCooldownCount + guardSummary.hardCooldownCount + guardSummary.disabledSessionCount}
              </span>
            )}
          </button>
        ))}
      </div>

      {/* Tab content */}
      {tab === "overview" && <OverviewContent status={status} />}
      {tab === "markets" && <MarketsContent markets={markets} />}
      {tab === "orders" && <OrdersContent orders={orders} />}
      {tab === "fills" && <FillsContent fills={fills} />}
      {tab === "guard" && <GuardContent markets={guardMarkets} summary={guardSummary} />}
      {tab === "logs" && <LogsContent text={logText} />}
    </div>
  );
}

/* ── Sub-tab content ──────────────────────────────────────────── */

function OverviewContent({ status }: { status: ShadowStatus | null }) {
  if (!status) {
    return (
      <div className="text-dark-500 text-sm text-center py-10 bg-dark-800/30 rounded-lg border border-dark-800">
        Shadow mode no activo. Pulsa Start para conectar a Polymarket.
      </div>
    );
  }

  const m = status.metrics ?? {};
  return (
    <div className="card">
      <h3 className="text-xs font-medium text-dark-400 uppercase tracking-wider mb-3">Resumen de sesion</h3>
      <div className="grid grid-cols-2 sm:grid-cols-3 gap-3 text-xs">
        <InfoRow label="Status" value={status.status} />
        <InfoRow label="WebSocket" value={status.wsConnected ? "Conectado" : "Desconectado"} color={status.wsConnected ? "green" : "red"} />
        <InfoRow label="Ciclos" value={String(status.cycleCount)} />
        <InfoRow label="Mercados vivos" value={String(status.liveMarkets)} />
        <InfoRow label="Ordenes activas" value={String(status.activeOrders)} />
        <InfoRow label="Total fills" value={String(m.totalFills ?? 0)} />
        <InfoRow label="Total quotes" value={String(m.totalQuotes ?? 0)} />
        <InfoRow label="Inicio" value={status.startedAt ? new Date(status.startedAt).toLocaleString() : "N/A"} />
      </div>
    </div>
  );
}

function MarketsContent({ markets }: { markets: ShadowMarket[] }) {
  return (
    <DataTable
      columns={[
        { key: "question", label: "Market", render: (m: ShadowMarket) => <span className="max-w-[260px] truncate block">{m.question}</span>, sortable: true, sortValue: (m) => m.question },
        { key: "outcome", label: "Out", render: (m: ShadowMarket) => <span className="text-dark-400">{m.outcome}</span> },
        { key: "bid", label: "Bid", align: "right", render: (m: ShadowMarket) => <span className="font-mono text-accent-green">{m.liveBestBid.toFixed(3)}</span>, sortable: true, sortValue: (m) => m.liveBestBid },
        { key: "ask", label: "Ask", align: "right", render: (m: ShadowMarket) => <span className="font-mono text-accent-red">{m.liveBestAsk.toFixed(3)}</span>, sortable: true, sortValue: (m) => m.liveBestAsk },
        { key: "mid", label: "Mid", align: "right", render: (m: ShadowMarket) => <span className="font-mono">{m.liveMid.toFixed(4)}</span>, sortable: true, sortValue: (m) => m.liveMid },
        { key: "spread", label: "Spread", align: "right", render: (m: ShadowMarket) => <span className="font-mono">{m.liveSpread.toFixed(4)}</span>, sortable: true, sortValue: (m) => m.liveSpread },
        { key: "trades", label: "Trades", align: "right", render: (m: ShadowMarket) => <span className="font-mono text-dark-400">{m.tradeCount}</span>, sortable: true, sortValue: (m) => m.tradeCount },
        { key: "regime", label: "Regime", render: (m: ShadowMarket) => <RegimeBadge regime={m.regime} /> },
      ]}
      data={markets}
      rowKey={(m) => m.tokenId}
      emptyMessage="No hay mercados descubiertos"
      searchPlaceholder="Buscar mercado..."
      searchFilter={(m, q) => m.question.toLowerCase().includes(q) || m.outcome.toLowerCase().includes(q)}
      maxHeight="500px"
    />
  );
}

function OrdersContent({ orders }: { orders: ShadowOrder[] }) {
  return (
    <DataTable
      columns={[
        { key: "id", label: "ID", render: (o: ShadowOrder) => <span className="font-mono text-dark-500">{o.orderId}</span> },
        { key: "market", label: "Market", render: (o: ShadowOrder) => <span className="max-w-[200px] truncate block">{o.question} [{o.outcome}]</span> },
        { key: "side", label: "Side", render: (o: ShadowOrder) => <span className={o.side === "BUY" ? "badge-green" : "badge-red"}>{o.side}</span> },
        { key: "price", label: "Price", align: "right", render: (o: ShadowOrder) => <span className="font-mono">{o.price.toFixed(2)}</span>, sortable: true, sortValue: (o) => o.price },
        { key: "size", label: "Size", align: "right", render: (o: ShadowOrder) => <span className="font-mono">{o.size}</span>, sortable: true, sortValue: (o) => o.size },
        { key: "cap", label: "Cap%", align: "right", render: (o: ShadowOrder) => {
          const pct = (o.capitalShare ?? 0) * 100;
          const cls = pct > 25 ? "text-accent-red" : pct > 20 ? "text-yellow-400" : "text-dark-200";
          return <span className={`font-mono font-semibold ${cls}`}>{pct.toFixed(1)}%</span>;
        }, sortable: true, sortValue: (o) => o.capitalShare ?? 0 },
        { key: "edge", label: "Edge", align: "right", render: (o: ShadowOrder) => <span className="font-mono text-dark-400">{o.edgeScore?.toFixed(3)}</span>, sortable: true, sortValue: (o) => o.edgeScore ?? 0 },
        { key: "regime", label: "Regime", render: (o: ShadowOrder) => <RegimeBadge regime={o.regime} /> },
      ]}
      data={orders}
      rowKey={(o) => o.orderId}
      emptyMessage="No hay ordenes hipoteticas activas"
      searchFilter={(o, q) => o.question.toLowerCase().includes(q) || o.orderId.includes(q)}
    />
  );
}

function FillsContent({ fills }: { fills: ShadowFillItem[] }) {
  const reversed = [...fills].reverse();
  return (
    <DataTable
      columns={[
        { key: "id", label: "ID", render: (f: ShadowFillItem) => <span className="font-mono text-dark-500">{f.fillId}</span> },
        { key: "market", label: "Market", render: (f: ShadowFillItem) => <span className="max-w-[200px] truncate block">{f.question} [{f.outcome}]</span> },
        { key: "side", label: "Side", render: (f: ShadowFillItem) => <span className={f.side === "BUY" ? "badge-green" : "badge-red"}>{f.side}</span> },
        { key: "price", label: "Price", align: "right", render: (f: ShadowFillItem) => <span className="font-mono">{f.fillPrice.toFixed(3)}</span>, sortable: true, sortValue: (f) => f.fillPrice },
        { key: "size", label: "Size", align: "right", render: (f: ShadowFillItem) => <span className="font-mono">{f.fillSize}</span> },
        { key: "slip", label: "Slippage", align: "right", render: (f: ShadowFillItem) => <span className="font-mono text-dark-400">{f.slippage.toFixed(4)}</span>, sortable: true, sortValue: (f) => f.slippage },
        { key: "pnl", label: "PnL", align: "right", render: (f: ShadowFillItem) => <span className={`font-mono ${f.estimatedPnl >= 0 ? "text-accent-green" : "text-accent-red"}`}>{f.estimatedPnl.toFixed(4)}</span>, sortable: true, sortValue: (f) => f.estimatedPnl },
        { key: "toxic", label: "Toxic", align: "center", render: (f: ShadowFillItem) => f.toxic ? <span className="px-1.5 py-0.5 rounded bg-accent-red/20 text-accent-red text-[10px]">TOXIC</span> : null },
      ]}
      data={reversed}
      rowKey={(f) => f.fillId}
      emptyMessage="No hay fills hipoteticos todavia"
      searchFilter={(f, q) => f.question.toLowerCase().includes(q) || f.fillId.includes(q)}
      maxHeight="500px"
    />
  );
}

function GuardContent({ markets, summary }: { markets: GuardMarketData[]; summary: GuardSummary | null }) {
  return (
    <div className="space-y-4">
      {summary && (
        <div className="grid grid-cols-2 sm:grid-cols-4 lg:grid-cols-6 gap-3">
          <MetricCard label="Markets Tracked" value={String(summary.marketsTracked)} />
          <MetricCard label="Soft Cooldown" value={String(summary.softCooldownCount)} color={summary.softCooldownCount > 0 ? "red" : undefined} />
          <MetricCard label="Hard Cooldown" value={String(summary.hardCooldownCount)} color={summary.hardCooldownCount > 0 ? "red" : undefined} />
          <MetricCard label="Disabled" value={String(summary.disabledSessionCount)} color={summary.disabledSessionCount > 0 ? "red" : undefined} />
          <MetricCard label="HHI" value={summary.hhi != null ? String(summary.hhi) : "N/A"} tooltip="Edge" />
          <MetricCard label="PnL Saved (est.)" value={summary.estimatedPnlSaved != null ? summary.estimatedPnlSaved.toFixed(4) : "0"} color="green" />
        </div>
      )}
      {summary?.topFillsMarket && (
        <div className="bg-dark-800/60 rounded-lg p-3 border border-dark-700/50 text-xs">
          <span className="text-dark-500">Top concentracion:</span>{" "}
          <span className="font-mono text-dark-200">{summary.topFillsMarket}</span>{" "}
          <span className="text-dark-500">share=</span>
          <span className={`font-mono ${(summary.topFillsShare ?? 0) > 0.3 ? "text-accent-red" : "text-dark-200"}`}>
            {((summary.topFillsShare ?? 0) * 100).toFixed(1)}%
          </span>{" "}
          <span className="text-dark-500">pnl=</span>
          <span className={`font-mono ${(summary.topFillsPnl ?? 0) >= 0 ? "text-accent-green" : "text-accent-red"}`}>
            {(summary.topFillsPnl ?? 0).toFixed(4)}
          </span>
        </div>
      )}

      <DataTable
        columns={[
          { key: "status", label: "Guard", render: (m: GuardMarketData) => <GuardStatusBadge status={m.status} /> },
          { key: "tokenId", label: "Market", render: (m: GuardMarketData) => <span className="font-mono text-[10px] max-w-[120px] truncate block">{m.tokenId.substring(0, 16)}</span> },
          { key: "fills", label: "Fills", align: "right", render: (m: GuardMarketData) => <span className="font-mono">{m.sessionFills}</span>, sortable: true, sortValue: (m) => m.sessionFills },
          { key: "pnl", label: "PnL", align: "right", render: (m: GuardMarketData) => <span className={`font-mono ${m.sessionPnl >= 0 ? "text-accent-green" : "text-accent-red"}`}>{m.sessionPnl.toFixed(4)}</span>, sortable: true, sortValue: (m) => m.sessionPnl },
          { key: "share", label: "Fill Share", align: "right", render: (m: GuardMarketData) => {
            const pct = m.fillsShare * 100;
            return <span className={`font-mono ${pct > 30 ? "text-accent-red font-bold" : pct > 20 ? "text-yellow-400" : ""}`}>{pct.toFixed(1)}%</span>;
          }, sortable: true, sortValue: (m) => m.fillsShare },
          { key: "pnl5m", label: "PnL 5m", align: "right", render: (m: GuardMarketData) => <span className={`font-mono ${m.rollingPnl5m >= 0 ? "text-accent-green" : "text-accent-red"}`}>{m.rollingPnl5m.toFixed(4)}</span>, sortable: true, sortValue: (m) => m.rollingPnl5m },
          { key: "stale", label: "Stale Rate", align: "right", render: (m: GuardMarketData) => <span className={`font-mono ${m.staleCancelRate > 0.8 ? "text-accent-red" : ""}`}>{(m.staleCancelRate * 100).toFixed(0)}%</span>, sortable: true, sortValue: (m) => m.staleCancelRate },
          { key: "slip", label: "Avg Slip", align: "right", render: (m: GuardMarketData) => <span className="font-mono text-dark-400">{m.avgSlippage.toFixed(5)}</span>, sortable: true, sortValue: (m) => m.avgSlippage },
          { key: "fillRate", label: "Fill Rate", align: "right", render: (m: GuardMarketData) => <span className="font-mono">{(m.fillRate * 100).toFixed(1)}%</span>, sortable: true, sortValue: (m) => m.fillRate },
          { key: "penalty", label: "Penalty", align: "right", render: (m: GuardMarketData) => <span className={`font-mono ${m.guardPenalty > 0 ? "text-accent-red" : "text-dark-500"}`}>{m.guardPenalty.toFixed(2)}</span>, sortable: true, sortValue: (m) => m.guardPenalty },
          { key: "neg", label: "Consec-", align: "right", render: (m: GuardMarketData) => <span className={`font-mono ${m.consecutiveNegFills >= 4 ? "text-accent-red font-bold" : ""}`}>{m.consecutiveNegFills}</span>, sortable: true, sortValue: (m) => m.consecutiveNegFills },
          { key: "reason", label: "Last Reason", render: (m: GuardMarketData) => <span className="text-dark-400 text-[10px]">{m.lastReason}</span> },
        ]}
        data={markets}
        rowKey={(m) => m.tokenId}
        emptyMessage="No hay datos de guard todavia. Espera unos ciclos."
        searchFilter={(m, q) => m.tokenId.includes(q) || m.status.toLowerCase().includes(q) || m.lastReason.toLowerCase().includes(q)}
        searchPlaceholder="Buscar por market, status, reason..."
        maxHeight="500px"
      />
    </div>
  );
}

function GuardStatusBadge({ status }: { status: string }) {
  const cls: Record<string, string> = {
    ACTIVE: "bg-green-900/40 text-green-400",
    SOFT_COOLDOWN: "bg-yellow-900/40 text-yellow-400",
    HARD_COOLDOWN: "bg-orange-900/40 text-orange-400",
    DISABLED_SESSION: "bg-red-900/40 text-red-400",
  };
  return <span className={`text-[10px] px-1.5 py-0.5 rounded font-medium ${cls[status] ?? "bg-dark-700 text-dark-400"}`}>{status}</span>;
}

function LogsContent({ text }: { text: string }) {
  const downloadLogs = () => {
    const blob = new Blob([text], { type: "text/plain" });
    const url = URL.createObjectURL(blob);
    const a = document.createElement("a");
    a.href = url;
    a.download = `shadow-logs-${new Date().toISOString().slice(0, 19).replace(/:/g, "-")}.txt`;
    a.click();
    URL.revokeObjectURL(url);
  };

  return (
    <div>
      <div className="flex items-center justify-between mb-3">
        <span className="text-xs text-dark-400">Logs del shadow engine (auto-refresh cada 10s)</span>
        <button onClick={downloadLogs} className="px-3 py-1 rounded text-xs font-medium bg-dark-700 text-dark-300 hover:bg-dark-600 transition-colors">
          Descargar TXT
        </button>
      </div>
      <pre className="bg-dark-950 border border-dark-800 rounded-lg p-4 text-[11px] text-dark-300 font-mono overflow-auto max-h-[600px] whitespace-pre-wrap leading-relaxed">
        {text || "Cargando logs..."}
      </pre>
    </div>
  );
}

/* ── Shared sub-components ──────────────────────────────────────── */

function MetricCard({ label, value, sub, color, tooltip }: { label: string; value: string; sub?: string; color?: string; tooltip?: string }) {
  const cls = color === "green" ? "text-accent-green" : color === "red" ? "text-accent-red" : "text-dark-200";
  return (
    <div className="bg-dark-800/60 rounded-lg px-3 py-2.5 border border-dark-700/50">
      <div className="text-[10px] text-dark-500 uppercase flex items-center">
        {label}
        {tooltip && <InfoTooltip term={tooltip} />}
      </div>
      <div className={`text-sm font-mono font-medium ${cls} mt-0.5`}>{value}</div>
      {sub && <div className="text-[10px] text-dark-500 mt-0.5">{sub}</div>}
    </div>
  );
}

function InfoRow({ label, value, color }: { label: string; value: string; color?: string }) {
  const cls = color === "green" ? "text-accent-green" : color === "red" ? "text-accent-red" : "text-dark-200";
  return (
    <div className="flex justify-between py-1.5 border-b border-dark-800/50">
      <span className="text-dark-500">{label}</span>
      <span className={`font-mono ${cls}`}>{value}</span>
    </div>
  );
}

function RegimeBadge({ regime }: { regime: string | null | undefined }) {
  if (!regime) return <span className="text-dark-500">-</span>;
  const cls: Record<string, string> = {
    CALM: "bg-green-900/40 text-green-400",
    NORMAL: "bg-blue-900/40 text-blue-400",
    VOLATILE: "bg-yellow-900/40 text-yellow-400",
    CRISIS: "bg-red-900/40 text-red-400",
  };
  return <span className={`text-[10px] px-1.5 py-0.5 rounded font-medium ${cls[regime] ?? "bg-dark-700 text-dark-400"}`}>{regime}</span>;
}

function fmt(v: any): string { return v != null ? Number(v).toFixed(4) : "0.0000"; }
function pct(v: any): string { return v != null ? `${(Number(v) * 100).toFixed(1)}%` : "0.0%"; }
