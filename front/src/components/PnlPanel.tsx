"use client";

import { useApi } from "@/hooks/useApi";
import { api, PnL } from "@/lib/api";
import { AreaChart, Area, ResponsiveContainer, Tooltip, XAxis } from "recharts";

const mockChartData = [
  { day: "Mon", value: 12 },
  { day: "Tue", value: 45 },
  { day: "Wed", value: 28 },
  { day: "Thu", value: 67 },
  { day: "Fri", value: 52 },
  { day: "Sat", value: 89 },
  { day: "Sun", value: 68 },
];

export default function PnlPanel() {
  const { data, loading } = useApi<PnL>(api.getPnl);

  if (loading) return <PnlSkeleton />;
  if (!data) return null;

  return (
    <div className="card">
      <h2 className="text-sm font-medium text-dark-400 uppercase tracking-wider mb-4">
        Profit &amp; Loss
      </h2>
      <div className="grid grid-cols-3 gap-4 mb-4">
        <PnlMetric label="Realized" value={data.realized} />
        <PnlMetric label="Unrealized" value={data.unrealized} />
        <PnlMetric label="Daily" value={data.daily} />
      </div>
      <div className="h-32 mt-2">
        <ResponsiveContainer width="100%" height="100%">
          <AreaChart data={mockChartData}>
            <defs>
              <linearGradient id="pnlGradient" x1="0" y1="0" x2="0" y2="1">
                <stop offset="5%" stopColor="#00c853" stopOpacity={0.3} />
                <stop offset="95%" stopColor="#00c853" stopOpacity={0} />
              </linearGradient>
            </defs>
            <XAxis dataKey="day" axisLine={false} tickLine={false} tick={{ fill: "#8e8ea0", fontSize: 11 }} />
            <Tooltip
              contentStyle={{
                background: "#343541",
                border: "1px solid #40414f",
                borderRadius: "8px",
                fontSize: "12px",
              }}
              labelStyle={{ color: "#acacbe" }}
            />
            <Area type="monotone" dataKey="value" stroke="#00c853" fill="url(#pnlGradient)" strokeWidth={2} />
          </AreaChart>
        </ResponsiveContainer>
      </div>
    </div>
  );
}

function PnlMetric({ label, value }: { label: string; value: number }) {
  const color = value >= 0 ? "text-accent-green" : "text-accent-red";
  const sign = value >= 0 ? "+" : "";
  return (
    <div>
      <p className="text-xs text-dark-500 mb-1">{label}</p>
      <p className={`text-lg font-bold font-mono ${color}`}>
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
      <div className="h-32 bg-dark-800 rounded" />
    </div>
  );
}
