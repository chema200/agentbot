"use client";

import { useApi } from "@/hooks/useApi";
import { api, BotStatus } from "@/lib/api";

export default function StatusCard() {
  const { data, loading } = useApi<BotStatus>(api.getStatus, 2000);

  if (loading) return <StatusSkeleton />;

  const state = data?.botStatus || "STOPPED";
  const isConnected = data?.connection === "OK";

  const stateColor: Record<string, string> = {
    RUNNING: "bg-accent-green animate-pulse",
    PAUSED: "bg-accent-yellow",
    STOPPED: "bg-dark-500",
    STARTING: "bg-accent-blue animate-pulse",
    ERROR: "bg-accent-red animate-pulse",
  };

  return (
    <div className="card">
      <h2 className="text-sm font-medium text-dark-400 uppercase tracking-wider mb-4">
        Engine Status
      </h2>
      <div className="flex items-center justify-between">
        <div className="flex items-center gap-3">
          <div className={`h-3 w-3 rounded-full ${stateColor[state] || "bg-dark-500"}`} />
          <span className="text-lg font-semibold">{state}</span>
        </div>
        <div className={`text-sm font-medium ${isConnected ? "text-accent-green" : "text-accent-red"}`}>
          {isConnected ? "Connected" : "Disconnected"}
        </div>
      </div>
      <p className="text-xs text-dark-500 mt-3">
        Cycles: <span className="text-dark-300 font-mono">{data?.uptime ?? 0}</span>
      </p>
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
