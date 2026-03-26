"use client";

import { useApi } from "@/hooks/useApi";
import { api, Market } from "@/lib/api";

export default function MarketTable() {
  const { data: markets, loading } = useApi<Market[]>(api.getMarkets);

  if (loading) return <MarketSkeleton />;

  const sorted = [...(markets || [])].sort(
    (a, b) => (b.edgeScore ?? -999) - (a.edgeScore ?? -999)
  );

  return (
    <div className="card overflow-hidden">
      <h2 className="text-sm font-medium text-dark-400 uppercase tracking-wider mb-4">
        Markets &mdash; Edge Selection
      </h2>
      <div className="overflow-x-auto">
        <table className="w-full text-sm">
          <thead>
            <tr className="border-b border-dark-700">
              <th className="text-left py-2 px-2 text-dark-400 font-medium">Status</th>
              <th className="text-left py-2 px-2 text-dark-400 font-medium">Market</th>
              <th className="text-right py-2 px-2 text-dark-400 font-medium">Edge</th>
              <th className="text-right py-2 px-2 text-dark-400 font-medium">Rwd Eff</th>
              <th className="text-right py-2 px-2 text-dark-400 font-medium">Comp Den</th>
              <th className="text-right py-2 px-2 text-dark-400 font-medium">Spread</th>
              <th className="text-right py-2 px-2 text-dark-400 font-medium">Vol Pen</th>
              <th className="text-right py-2 px-2 text-dark-400 font-medium">Regime</th>
            </tr>
          </thead>
          <tbody>
            {sorted.map((m) => (
              <tr
                key={m.marketId}
                className={`border-b border-dark-800 transition-colors ${
                  m.selected
                    ? "hover:bg-dark-800/50"
                    : "opacity-40 hover:opacity-60"
                }`}
              >
                <td className="py-2 px-2">
                  <StatusBadge selected={m.selected} />
                </td>
                <td className="py-2 px-2 max-w-[180px] truncate text-xs">
                  {m.name}
                </td>
                <td className="py-2 px-2 text-right font-mono">
                  <EdgeBadge value={m.edgeScore} />
                </td>
                <td className="py-2 px-2 text-right font-mono text-dark-300 text-xs">
                  {m.rewardEfficiency != null ? (m.rewardEfficiency * 1000).toFixed(2) : "—"}
                </td>
                <td className="py-2 px-2 text-right font-mono text-xs">
                  <DensityBadge value={m.competitionDensity} />
                </td>
                <td className="py-2 px-2 text-right font-mono text-dark-300 text-xs">
                  ${m.spread.toFixed(3)}
                </td>
                <td className="py-2 px-2 text-right font-mono text-xs">
                  {m.volatilityPenalty != null ? (
                    <span className={m.volatilityPenalty > 0.5 ? "text-accent-red" : "text-dark-300"}>
                      {m.volatilityPenalty.toFixed(2)}
                    </span>
                  ) : "—"}
                </td>
                <td className="py-2 px-2 text-right">
                  <RegimeBadge regime={m.regime} />
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </div>
  );
}

function StatusBadge({ selected }: { selected: boolean }) {
  return selected ? (
    <span className="badge-green text-xs">ACTIVE</span>
  ) : (
    <span className="badge-red text-xs">SKIP</span>
  );
}

function EdgeBadge({ value }: { value: number | null }) {
  if (value == null) return <span className="text-dark-500">—</span>;
  let cls = "text-dark-400";
  if (value >= 1.0) cls = "text-accent-green";
  else if (value >= 0.5) cls = "text-accent-blue";
  else if (value < 0.3) cls = "text-accent-red";
  return <span className={`${cls} text-xs`}>{value.toFixed(2)}</span>;
}

function DensityBadge({ value }: { value: number | null }) {
  if (value == null) return <span className="text-dark-500">—</span>;
  const pct = (value * 100).toFixed(0);
  let cls = "text-dark-300";
  if (value > 0.8) cls = "text-accent-red";
  else if (value > 0.6) cls = "text-yellow-400";
  return <span className={cls}>{pct}%</span>;
}

function RegimeBadge({ regime }: { regime: string | null }) {
  if (!regime) return <span className="text-dark-500">—</span>;
  const cls: Record<string, string> = {
    CALM: "badge-green",
    NORMAL: "badge-blue",
    VOLATILE: "badge-yellow",
    CRISIS: "badge-red",
  };
  return <span className={cls[regime] || "badge-blue"} style={{ fontSize: "10px" }}>{regime}</span>;
}

function MarketSkeleton() {
  return (
    <div className="card animate-pulse">
      <div className="h-4 w-20 bg-dark-700 rounded mb-4" />
      {[...Array(5)].map((_, i) => (
        <div key={i} className="h-8 bg-dark-800 rounded mb-2" />
      ))}
    </div>
  );
}
