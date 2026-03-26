"use client";

import { useApi } from "@/hooks/useApi";
import { api, Order, Fill, Market } from "@/lib/api";

export default function QuickStats() {
  const { data: orders } = useApi<Order[]>(api.getOrders, 3000);
  const { data: fills } = useApi<Fill[]>(api.getFills, 3000);
  const { data: markets } = useApi<Market[]>(api.getMarkets, 3000);

  const activeOrders = orders?.filter((o) => o.status === "OPEN").length ?? 0;
  const totalFills = fills?.length ?? 0;
  const totalMarkets = markets?.length ?? 0;
  const filledOrders = orders?.filter((o) => o.status === "FILLED").length ?? 0;
  const totalOrders = orders?.length ?? 0;
  const fillRate = totalOrders > 0 ? Math.round((filledOrders / totalOrders) * 100) : 0;

  return (
    <div className="card">
      <h2 className="text-sm font-medium text-dark-400 uppercase tracking-wider mb-2">
        Quick Stats
      </h2>
      <div className="grid grid-cols-2 gap-4 mt-3">
        <Stat label="Active Orders" value={activeOrders} />
        <Stat label="Total Fills" value={totalFills} />
        <Stat label="Markets Tracked" value={totalMarkets} />
        <Stat label="Fill Rate" value={`${fillRate}%`} color={fillRate > 50 ? "text-accent-green" : "text-dark-100"} />
      </div>
    </div>
  );
}

function Stat({ label, value, color = "text-dark-100" }: { label: string; value: string | number; color?: string }) {
  return (
    <div>
      <p className="text-xs text-dark-500">{label}</p>
      <p className={`text-2xl font-bold font-mono ${color}`}>{value}</p>
    </div>
  );
}
