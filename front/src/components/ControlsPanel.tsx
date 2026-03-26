"use client";

import { useState } from "react";

type BotState = "RUNNING" | "PAUSED" | "STOPPED";

export default function ControlsPanel() {
  const [state, setState] = useState<BotState>("STOPPED");

  return (
    <div className="card">
      <h2 className="text-sm font-medium text-dark-400 uppercase tracking-wider mb-4">
        Controls
      </h2>
      <div className="flex items-center gap-3">
        <button
          onClick={() => setState("RUNNING")}
          disabled={state === "RUNNING"}
          className="flex-1 py-2.5 px-4 rounded-lg font-medium text-sm transition-all duration-200 bg-accent-green/15 text-accent-green hover:bg-accent-green/25 disabled:opacity-40 disabled:cursor-not-allowed"
        >
          ▶ Start
        </button>
        <button
          onClick={() => setState("PAUSED")}
          disabled={state === "PAUSED" || state === "STOPPED"}
          className="flex-1 py-2.5 px-4 rounded-lg font-medium text-sm transition-all duration-200 bg-accent-yellow/15 text-accent-yellow hover:bg-accent-yellow/25 disabled:opacity-40 disabled:cursor-not-allowed"
        >
          ⏸ Pause
        </button>
        <button
          onClick={() => setState("STOPPED")}
          disabled={state === "STOPPED"}
          className="flex-1 py-2.5 px-4 rounded-lg font-medium text-sm transition-all duration-200 bg-accent-red/15 text-accent-red hover:bg-accent-red/25 disabled:opacity-40 disabled:cursor-not-allowed"
        >
          ⏹ Stop
        </button>
      </div>
      <p className="text-xs text-dark-500 mt-3 text-center">
        Status: <span className={
          state === "RUNNING" ? "text-accent-green" :
          state === "PAUSED" ? "text-accent-yellow" : "text-dark-400"
        }>{state}</span>
      </p>
    </div>
  );
}
