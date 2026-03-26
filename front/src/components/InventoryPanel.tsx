"use client";

import { useApi } from "@/hooks/useApi";
import { api, Inventory } from "@/lib/api";

export default function InventoryPanel() {
  const { data, loading } = useApi<Inventory>(api.getInventory);

  if (loading) return <InventorySkeleton />;
  if (!data) return null;

  const max = Math.max(data.yesExposure, data.noExposure, 1);

  return (
    <div className="card">
      <h2 className="text-sm font-medium text-dark-400 uppercase tracking-wider mb-4">
        Inventory
      </h2>
      <div className="space-y-4">
        <ExposureBar label="YES Exposure" value={data.yesExposure} max={max} color="bg-accent-green" />
        <ExposureBar label="NO Exposure" value={data.noExposure} max={max} color="bg-accent-red" />
        <div className="border-t border-dark-700 pt-3 mt-3">
          <div className="flex justify-between items-center">
            <span className="text-dark-400 text-sm">Net Exposure</span>
            <span className={`text-lg font-bold font-mono ${data.netExposure >= 0 ? "text-accent-green" : "text-accent-red"}`}>
              ${data.netExposure.toFixed(2)}
            </span>
          </div>
        </div>
      </div>
    </div>
  );
}

function ExposureBar({ label, value, max, color }: { label: string; value: number; max: number; color: string }) {
  const pct = (value / max) * 100;
  return (
    <div>
      <div className="flex justify-between text-sm mb-1">
        <span className="text-dark-300">{label}</span>
        <span className="font-mono text-dark-200">${value.toFixed(2)}</span>
      </div>
      <div className="h-2 bg-dark-800 rounded-full overflow-hidden">
        <div
          className={`h-full rounded-full ${color} transition-all duration-500`}
          style={{ width: `${pct}%` }}
        />
      </div>
    </div>
  );
}

function InventorySkeleton() {
  return (
    <div className="card animate-pulse">
      <div className="h-4 w-24 bg-dark-700 rounded mb-4" />
      <div className="space-y-4">
        {[...Array(3)].map((_, i) => (
          <div key={i} className="h-6 bg-dark-800 rounded" />
        ))}
      </div>
    </div>
  );
}
