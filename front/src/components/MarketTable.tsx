"use client";

import { useApi } from "@/hooks/useApi";
import { api, Market } from "@/lib/api";

export default function MarketTable() {
  const { data: markets, loading } = useApi<Market[]>(api.getMarkets);

  if (loading) return <MarketSkeleton />;

  return (
    <div className="card overflow-hidden">
      <h2 className="text-sm font-medium text-dark-400 uppercase tracking-wider mb-4">
        Markets
      </h2>
      <div className="overflow-x-auto">
        <table className="w-full text-sm">
          <thead>
            <tr className="border-b border-dark-700">
              <th className="text-left py-2 px-3 text-dark-400 font-medium">Market</th>
              <th className="text-right py-2 px-3 text-dark-400 font-medium">Bid</th>
              <th className="text-right py-2 px-3 text-dark-400 font-medium">Ask</th>
              <th className="text-right py-2 px-3 text-dark-400 font-medium">Spread</th>
              <th className="text-right py-2 px-3 text-dark-400 font-medium">Volume</th>
              <th className="text-right py-2 px-3 text-dark-400 font-medium">Liquidity</th>
            </tr>
          </thead>
          <tbody>
            {markets?.map((m) => (
              <tr key={m.marketId} className="border-b border-dark-800 hover:bg-dark-800/50 transition-colors">
                <td className="py-2.5 px-3 max-w-[220px] truncate">{m.name}</td>
                <td className="py-2.5 px-3 text-right font-mono text-accent-green">${m.bestBid.toFixed(2)}</td>
                <td className="py-2.5 px-3 text-right font-mono text-accent-red">${m.bestAsk.toFixed(2)}</td>
                <td className="py-2.5 px-3 text-right font-mono text-dark-300">${m.spread.toFixed(2)}</td>
                <td className="py-2.5 px-3 text-right font-mono text-dark-300">{formatVolume(m.volume)}</td>
                <td className="py-2.5 px-3 text-right">
                  <LiquidityBadge score={m.liquidityScore} />
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </div>
  );
}

function LiquidityBadge({ score }: { score: number }) {
  let cls = "badge-red";
  if (score >= 8) cls = "badge-green";
  else if (score >= 6) cls = "badge-blue";
  else if (score >= 4) cls = "badge-yellow";
  return <span className={cls}>{score.toFixed(1)}</span>;
}

function formatVolume(vol: number): string {
  if (vol >= 1_000_000) return `$${(vol / 1_000_000).toFixed(1)}M`;
  if (vol >= 1_000) return `$${(vol / 1_000).toFixed(0)}K`;
  return `$${vol}`;
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
