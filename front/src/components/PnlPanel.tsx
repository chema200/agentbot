"use client";

import { useApi } from "@/hooks/useApi";
import { api, PnL } from "@/lib/api";

export default function PnlPanel() {
  const { data, loading } = useApi<PnL>(api.getPnl);

  if (loading) return <PnlSkeleton />;
  if (!data) return null;

  const tradingPnl = data.tradingPnl ?? data.realized;
  const rewardPnl = data.rewardPnl ?? 0;
  const totalPnl = data.totalPnl ?? data.daily;
  const fees = data.fees ?? 0;

  return (
    <div className="card">
      <h2 className="text-sm font-medium text-dark-400 uppercase tracking-wider mb-4">
        Profit &amp; Loss
      </h2>

      <div className="grid grid-cols-3 gap-4 mb-4">
        <PnlMetric label="Trading PnL" value={tradingPnl} />
        <PnlMetric label="Reward PnL" value={rewardPnl} accent />
        <PnlMetric label="Total PnL" value={totalPnl} bold />
      </div>

      <div className="border-t border-dark-800 pt-3 mt-1">
        <div className="grid grid-cols-3 gap-4">
          <PnlMetric label="Unrealized" value={data.unrealized} small />
          <PnlMetric label="Fees Paid" value={-fees} small />
          <PnlMetric label="Net (incl. unreal)" value={totalPnl + data.unrealized} small />
        </div>
      </div>

      <div className="mt-4 pt-3 border-t border-dark-800">
        <div className="flex items-center justify-between text-xs text-dark-500">
          <span>Reward share of profit</span>
          <span className="font-mono text-accent-blue">
            {totalPnl !== 0
              ? `${((rewardPnl / Math.abs(totalPnl)) * 100).toFixed(1)}%`
              : "—"}
          </span>
        </div>
        <div className="mt-2 h-2 bg-dark-800 rounded-full overflow-hidden flex">
          <div
            className="bg-accent-green h-full transition-all duration-500"
            style={{
              width: `${
                totalPnl > 0
                  ? Math.max(0, Math.min(100, (tradingPnl / totalPnl) * 100))
                  : 50
              }%`,
            }}
          />
          <div
            className="bg-accent-blue h-full transition-all duration-500"
            style={{
              width: `${
                totalPnl > 0
                  ? Math.max(0, Math.min(100, (rewardPnl / totalPnl) * 100))
                  : 50
              }%`,
            }}
          />
        </div>
        <div className="flex justify-between mt-1 text-[10px] text-dark-500">
          <span className="flex items-center gap-1">
            <span className="inline-block w-2 h-2 rounded-full bg-accent-green" />
            Trading
          </span>
          <span className="flex items-center gap-1">
            <span className="inline-block w-2 h-2 rounded-full bg-accent-blue" />
            Rewards
          </span>
        </div>
      </div>
    </div>
  );
}

function PnlMetric({
  label,
  value,
  accent,
  bold,
  small,
}: {
  label: string;
  value: number;
  accent?: boolean;
  bold?: boolean;
  small?: boolean;
}) {
  const color = accent
    ? "text-accent-blue"
    : value >= 0
    ? "text-accent-green"
    : "text-accent-red";
  const sign = value >= 0 ? "+" : "";
  const sizeClass = small ? "text-sm" : bold ? "text-xl" : "text-lg";
  const weightClass = bold ? "font-extrabold" : "font-bold";
  return (
    <div>
      <p className={`text-xs text-dark-500 mb-1 ${small ? "text-[10px]" : ""}`}>
        {label}
      </p>
      <p className={`${sizeClass} ${weightClass} font-mono ${color}`}>
        {sign}${value.toFixed(2)}
      </p>
    </div>
  );
}

function PnlSkeleton() {
  return (
    <div className="card animate-pulse">
      <div className="h-4 w-28 bg-dark-700 rounded mb-4" />
      <div className="grid grid-cols-3 gap-4 mb-4">
        {[...Array(3)].map((_, i) => (
          <div key={i} className="h-12 bg-dark-800 rounded" />
        ))}
      </div>
      <div className="h-20 bg-dark-800 rounded" />
    </div>
  );
}
