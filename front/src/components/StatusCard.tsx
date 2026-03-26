"use client";

import { useApi } from "@/hooks/useApi";
import { api, BotStatus } from "@/lib/api";

export default function StatusCard() {
  const { data, loading } = useApi<BotStatus>(api.getStatus);

  if (loading) return <StatusSkeleton />;

  const isRunning = data?.botStatus === "RUNNING";
  const isConnected = data?.connection === "OK";

  return (
    <div className="card">
      <h2 className="text-sm font-medium text-dark-400 uppercase tracking-wider mb-4">
        Bot Status
      </h2>
      <div className="flex items-center justify-between">
        <div className="flex items-center gap-3">
          <div className={`h-3 w-3 rounded-full ${isRunning ? "bg-accent-green animate-pulse" : "bg-accent-yellow"}`} />
          <span className="text-lg font-semibold">
            {data?.botStatus || "UNKNOWN"}
          </span>
        </div>
        <div className={`text-sm font-medium ${isConnected ? "text-accent-green" : "text-accent-red"}`}>
          {isConnected ? "Connected" : "Disconnected"}
        </div>
      </div>
      {data?.uptime && (
        <p className="text-xs text-dark-500 mt-3">
          Uptime: {formatUptime(data.uptime)}
        </p>
      )}
    </div>
  );
}

function StatusSkeleton() {
  return (
    <div className="card animate-pulse">
      <div className="h-4 w-24 bg-dark-700 rounded mb-4" />
      <div className="h-6 w-32 bg-dark-700 rounded" />
    </div>
  );
}

function formatUptime(seconds: number): string {
  const d = Math.floor(seconds / 86400);
  const h = Math.floor((seconds % 86400) / 3600);
  const m = Math.floor((seconds % 3600) / 60);
  if (d > 0) return `${d}d ${h}h ${m}m`;
  if (h > 0) return `${h}h ${m}m`;
  return `${m}m`;
}
