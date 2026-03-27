"use client";

import { useState } from "react";
import { api, BacktestResult, MonteCarloResult } from "@/lib/api";

const PROFILES = [
  "BASELINE", "HIGH_COMPETITION", "LOW_REWARD",
  "HIGH_VOLATILITY", "FREQUENT_INFORMED", "HIGH_LATENCY", "CRISIS_HEAVY",
];

const PRESETS = [
  { label: "Quick (100 cycles)", cycles: 100 },
  { label: "Medium (500 cycles)", cycles: 500 },
  { label: "1h sim (1800 cycles)", cycles: 1800 },
  { label: "6h sim (10800 cycles)", cycles: 10800 },
];

export default function BacktestPanel() {
  const [profile, setProfile] = useState("BASELINE");
  const [cycles, setCycles] = useState(500);
  const [mcSeeds, setMcSeeds] = useState(20);
  const [loading, setLoading] = useState(false);
  const [lastResult, setLastResult] = useState<BacktestResult | null>(null);
  const [mcResult, setMcResult] = useState<MonteCarloResult | null>(null);
  const [history, setHistory] = useState<BacktestResult[]>([]);
  const [mcHistory, setMcHistory] = useState<MonteCarloResult[]>([]);
  const [tab, setTab] = useState<"single" | "mc" | "history">("single");

  const runBacktest = async () => {
    setLoading(true);
    try {
      const result = await api.runBacktest(cycles, Math.floor(Math.random() * 1e9), profile);
      setLastResult(result);
    } catch (e) { console.error(e); }
    setLoading(false);
  };

  const runMC = async () => {
    setLoading(true);
    try {
      const result = await api.runMonteCarlo(cycles, mcSeeds, profile);
      setMcResult(result);
    } catch (e) { console.error(e); }
    setLoading(false);
  };

  const loadHistory = async () => {
    try {
      const [runs, mc] = await Promise.all([api.getBacktestRuns(), api.getMcRuns()]);
      setHistory(runs);
      setMcHistory(mc);
    } catch (e) { console.error(e); }
  };

  return (
    <div className="card">
      <h2 className="text-sm font-medium text-dark-400 uppercase tracking-wider mb-4">
        Validation Framework
      </h2>

      <div className="flex gap-2 mb-4">
        {(["single", "mc", "history"] as const).map((t) => (
          <button
            key={t}
            onClick={() => { setTab(t); if (t === "history") loadHistory(); }}
            className={`px-3 py-1 rounded text-xs font-medium transition-colors ${
              tab === t ? "bg-accent-blue text-white" : "bg-dark-800 text-dark-400 hover:text-dark-200"
            }`}
          >
            {t === "single" ? "Backtest" : t === "mc" ? "Monte Carlo" : "History"}
          </button>
        ))}
      </div>

      <div className="flex flex-wrap gap-3 mb-4">
        <select
          value={profile}
          onChange={(e) => setProfile(e.target.value)}
          className="bg-dark-800 text-dark-200 rounded px-2 py-1 text-xs border border-dark-700"
        >
          {PROFILES.map((p) => <option key={p} value={p}>{p.replace(/_/g, " ")}</option>)}
        </select>

        <select
          value={cycles}
          onChange={(e) => setCycles(Number(e.target.value))}
          className="bg-dark-800 text-dark-200 rounded px-2 py-1 text-xs border border-dark-700"
        >
          {PRESETS.map((p) => <option key={p.cycles} value={p.cycles}>{p.label}</option>)}
        </select>

        {tab === "mc" && (
          <select
            value={mcSeeds}
            onChange={(e) => setMcSeeds(Number(e.target.value))}
            className="bg-dark-800 text-dark-200 rounded px-2 py-1 text-xs border border-dark-700"
          >
            {[10, 20, 50, 100].map((s) => <option key={s} value={s}>{s} seeds</option>)}
          </select>
        )}

        <button
          onClick={tab === "mc" ? runMC : runBacktest}
          disabled={loading || tab === "history"}
          className="px-4 py-1 rounded text-xs font-medium bg-accent-green text-dark-950 hover:brightness-110 disabled:opacity-50 transition-all"
        >
          {loading ? "Running..." : tab === "mc" ? "Run Monte Carlo" : "Run Backtest"}
        </button>
      </div>

      {tab === "single" && lastResult && <SingleResult r={lastResult} />}
      {tab === "mc" && mcResult && <MCResult r={mcResult} />}
      {tab === "history" && <HistoryView runs={history} mcRuns={mcHistory} />}
    </div>
  );
}

function SingleResult({ r }: { r: BacktestResult }) {
  return (
    <div>
      <div className="grid grid-cols-2 sm:grid-cols-4 gap-3 mb-3">
        <Metric label="Total PnL" value={r.totalPnl.toFixed(2)} color={r.totalPnl >= 0 ? "green" : "red"} />
        <Metric label="Trading PnL" value={r.tradingPnl.toFixed(2)} />
        <Metric label="Reward PnL" value={r.rewardPnl.toFixed(2)} />
        <Metric label="Fills" value={`${r.totalFills} (${r.toxicFills} toxic)`} />
      </div>
      <div className="grid grid-cols-2 sm:grid-cols-4 gap-3 mb-3">
        <Metric label="Max Drawdown" value={r.maxDrawdown.toFixed(2)} color="red" />
        <Metric label="Win Rate" value={`${(r.winRate * 100).toFixed(0)}%`} />
        <Metric label="Avg PPF" value={r.avgProfitPerFill.toFixed(2)} />
        <Metric label="Adverse Rate" value={`${(r.adverseSelectionRate * 100).toFixed(0)}%`} />
      </div>
      <div className="grid grid-cols-2 sm:grid-cols-4 gap-3">
        <Metric label="Max Exposure" value={r.maxExposure.toFixed(0)} />
        <Metric label="Fees" value={r.totalFees.toFixed(4)} />
        <Metric label="Duration" value={`${r.simulatedDurationSec}s sim`} />
        <Metric label="Time" value={`${r.elapsedMs}ms`} />
      </div>
    </div>
  );
}

function MCResult({ r }: { r: MonteCarloResult }) {
  const pnls = r.individualRuns?.map((run) => run.totalPnl).sort((a, b) => a - b) ?? [];

  return (
    <div>
      <div className="grid grid-cols-2 sm:grid-cols-4 gap-3 mb-3">
        <Metric label="Avg PnL" value={r.avgPnl.toFixed(2)} color={r.avgPnl >= 0 ? "green" : "red"} />
        <Metric label="Median PnL" value={r.medianPnl.toFixed(2)} />
        <Metric label="Std Dev" value={r.stdPnl.toFixed(2)} />
        <Metric label="Sharpe" value={r.sharpeRatio.toFixed(2)} color={r.sharpeRatio > 0.5 ? "green" : "yellow"} />
      </div>
      <div className="grid grid-cols-2 sm:grid-cols-4 gap-3 mb-3">
        <Metric label="Win Rate" value={`${(r.winRate * 100).toFixed(0)}%`} color={r.winRate > 0.5 ? "green" : "red"} />
        <Metric label="Min PnL" value={r.minPnl.toFixed(2)} color="red" />
        <Metric label="Max PnL" value={r.maxPnl.toFixed(2)} color="green" />
        <Metric label="Max DD" value={r.maxDrawdown.toFixed(2)} color="red" />
      </div>
      <div className="grid grid-cols-2 sm:grid-cols-4 gap-3 mb-4">
        <Metric label="Avg Fills" value={r.avgFills.toFixed(1)} />
        <Metric label="Avg Toxic" value={r.avgToxicFills.toFixed(1)} />
        <Metric label="Seeds" value={String(r.numSeeds)} />
        <Metric label="Time" value={`${(r.elapsedMs / 1000).toFixed(1)}s`} />
      </div>

      {pnls.length > 0 && <PnlDistribution pnls={pnls} />}
    </div>
  );
}

function PnlDistribution({ pnls }: { pnls: number[] }) {
  const min = Math.min(...pnls);
  const max = Math.max(...pnls);
  const range = max - min || 1;
  const bucketCount = 12;
  const bucketSize = range / bucketCount;
  const buckets = new Array(bucketCount).fill(0);

  for (const p of pnls) {
    const idx = Math.min(Math.floor((p - min) / bucketSize), bucketCount - 1);
    buckets[idx]++;
  }

  const maxBucket = Math.max(...buckets);

  return (
    <div>
      <div className="text-xs text-dark-400 mb-2">PnL Distribution</div>
      <div className="flex items-end gap-[2px] h-16">
        {buckets.map((count, i) => {
          const pct = maxBucket > 0 ? count / maxBucket : 0;
          const midVal = min + (i + 0.5) * bucketSize;
          return (
            <div
              key={i}
              className={`flex-1 rounded-t-sm ${midVal >= 0 ? "bg-accent-green/70" : "bg-accent-red/70"}`}
              style={{ height: `${Math.max(pct * 100, 2)}%` }}
              title={`${midVal.toFixed(1)}: ${count} runs`}
            />
          );
        })}
      </div>
      <div className="flex justify-between text-[10px] text-dark-500 mt-1">
        <span>{min.toFixed(1)}</span>
        <span>{max.toFixed(1)}</span>
      </div>
    </div>
  );
}

function HistoryView({ runs, mcRuns }: { runs: BacktestResult[]; mcRuns: MonteCarloResult[] }) {
  return (
    <div className="space-y-4">
      {mcRuns.length > 0 && (
        <div>
          <div className="text-xs text-dark-400 uppercase mb-2">Monte Carlo Runs</div>
          <div className="overflow-x-auto">
            <table className="w-full text-xs">
              <thead>
                <tr className="border-b border-dark-700 text-dark-400">
                  <th className="text-left py-1 px-2">Profile</th>
                  <th className="text-right py-1 px-2">Seeds</th>
                  <th className="text-right py-1 px-2">Avg PnL</th>
                  <th className="text-right py-1 px-2">Win%</th>
                  <th className="text-right py-1 px-2">Sharpe</th>
                  <th className="text-right py-1 px-2">Max DD</th>
                </tr>
              </thead>
              <tbody>
                {mcRuns.map((r) => (
                  <tr key={r.mcRunId} className="border-b border-dark-800 hover:bg-dark-800/50">
                    <td className="py-1 px-2">{r.stressProfile}</td>
                    <td className="py-1 px-2 text-right font-mono">{r.numSeeds}</td>
                    <td className={`py-1 px-2 text-right font-mono ${r.avgPnl >= 0 ? "text-accent-green" : "text-accent-red"}`}>
                      {r.avgPnl.toFixed(2)}
                    </td>
                    <td className="py-1 px-2 text-right font-mono">{(r.winRate * 100).toFixed(0)}%</td>
                    <td className="py-1 px-2 text-right font-mono">{r.sharpeRatio.toFixed(2)}</td>
                    <td className="py-1 px-2 text-right font-mono text-accent-red">{r.maxDrawdown.toFixed(2)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </div>
      )}

      {runs.length > 0 && (
        <div>
          <div className="text-xs text-dark-400 uppercase mb-2">Recent Backtests ({runs.length})</div>
          <div className="overflow-x-auto max-h-64 overflow-y-auto">
            <table className="w-full text-xs">
              <thead className="sticky top-0 bg-dark-900">
                <tr className="border-b border-dark-700 text-dark-400">
                  <th className="text-left py-1 px-2">ID</th>
                  <th className="text-left py-1 px-2">Profile</th>
                  <th className="text-right py-1 px-2">PnL</th>
                  <th className="text-right py-1 px-2">Fills</th>
                  <th className="text-right py-1 px-2">DD</th>
                  <th className="text-right py-1 px-2">Win%</th>
                </tr>
              </thead>
              <tbody>
                {runs.map((r) => (
                  <tr key={r.runId} className="border-b border-dark-800 hover:bg-dark-800/50">
                    <td className="py-1 px-2 font-mono text-dark-500">{r.runId.substring(0, 8)}</td>
                    <td className="py-1 px-2">{r.stressProfile}</td>
                    <td className={`py-1 px-2 text-right font-mono ${r.totalPnl >= 0 ? "text-accent-green" : "text-accent-red"}`}>
                      {r.totalPnl.toFixed(2)}
                    </td>
                    <td className="py-1 px-2 text-right font-mono">{r.totalFills}</td>
                    <td className="py-1 px-2 text-right font-mono">{r.maxDrawdown.toFixed(2)}</td>
                    <td className="py-1 px-2 text-right font-mono">{(r.winRate * 100).toFixed(0)}%</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </div>
      )}

      {runs.length === 0 && mcRuns.length === 0 && (
        <div className="text-dark-500 text-sm text-center py-4">No runs yet. Run a backtest first.</div>
      )}
    </div>
  );
}

function Metric({ label, value, color }: { label: string; value: string; color?: string }) {
  const cls = color === "green" ? "text-accent-green"
    : color === "red" ? "text-accent-red"
    : color === "yellow" ? "text-yellow-400"
    : "text-dark-200";
  return (
    <div className="bg-dark-800 rounded-lg px-3 py-2">
      <div className="text-[10px] text-dark-500 uppercase">{label}</div>
      <div className={`text-sm font-mono font-medium ${cls}`}>{value}</div>
    </div>
  );
}
