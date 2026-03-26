"use client";

import { useCallback } from "react";
import { useApi } from "@/hooks/useApi";
import { api, BotStatus } from "@/lib/api";

export default function ControlsPanel() {
  const { data, loading } = useApi<BotStatus>(api.getStatus, 2000);
  const state = data?.botStatus || "STOPPED";

  const handleStart = useCallback(async () => {
    try { await api.startEngine(); } catch (e) { console.error("Start failed", e); }
  }, []);

  const handlePause = useCallback(async () => {
    try { await api.pauseEngine(); } catch (e) { console.error("Pause failed", e); }
  }, []);

  const handleStop = useCallback(async () => {
    try { await api.stopEngine(); } catch (e) { console.error("Stop failed", e); }
  }, []);

  if (loading) {
    return (
      <div className="card animate-pulse">
        <div className="h-4 w-24 bg-dark-700 rounded mb-4" />
        <div className="h-10 bg-dark-800 rounded" />
      </div>
    );
  }

  return (
    <div className="card">
      <h2 className="text-sm font-medium text-dark-400 uppercase tracking-wider mb-4">
        Engine Controls
      </h2>
      <div className="flex items-center gap-3">
        <button
          onClick={handleStart}
          disabled={state === "RUNNING"}
          className="flex-1 py-2.5 px-4 rounded-lg font-medium text-sm transition-all duration-200 bg-accent-green/15 text-accent-green hover:bg-accent-green/25 disabled:opacity-40 disabled:cursor-not-allowed"
        >
          ▶ Start
        </button>
        <button
          onClick={handlePause}
          disabled={state === "PAUSED" || state === "STOPPED"}
          className="flex-1 py-2.5 px-4 rounded-lg font-medium text-sm transition-all duration-200 bg-accent-yellow/15 text-accent-yellow hover:bg-accent-yellow/25 disabled:opacity-40 disabled:cursor-not-allowed"
        >
          ⏸ Pause
        </button>
        <button
          onClick={handleStop}
          disabled={state === "STOPPED"}
          className="flex-1 py-2.5 px-4 rounded-lg font-medium text-sm transition-all duration-200 bg-accent-red/15 text-accent-red hover:bg-accent-red/25 disabled:opacity-40 disabled:cursor-not-allowed"
        >
          ⏹ Stop
        </button>
      </div>
      <p className="text-xs text-dark-500 mt-3 text-center">
        Engine:{" "}
        <span
          className={
            state === "RUNNING"
              ? "text-accent-green"
              : state === "PAUSED"
                ? "text-accent-yellow"
                : state === "ERROR"
                  ? "text-accent-red"
                  : "text-dark-400"
          }
        >
          {state}
        </span>
      </p>
    </div>
  );
}
