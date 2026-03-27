"use client";

import { useState } from "react";
import SimulationTab from "@/components/SimulationTab";
import ShadowTab from "@/components/ShadowTab";
import RealTradingTab from "@/components/RealTradingTab";

const TABS = [
  { id: "simulation", label: "Simulacion", icon: "M9.75 17L9 20l-1 1h8l-1-1-.75-3M3 13h18M5 17h14a2 2 0 002-2V5a2 2 0 00-2-2H5a2 2 0 00-2 2v10a2 2 0 002 2z" },
  { id: "shadow", label: "Shadow Polymarket", icon: "M15 12a3 3 0 11-6 0 3 3 0 016 0z M2.458 12C3.732 7.943 7.523 5 12 5c4.478 0 8.268 2.943 9.542 7-1.274 4.057-5.064 7-9.542 7-4.477 0-8.268-2.943-9.542-7z" },
  { id: "real", label: "Real Trading", icon: "M12 8c-1.657 0-3 .895-3 2s1.343 2 3 2 3 .895 3 2-1.343 2-3 2m0-8c1.11 0 2.08.402 2.599 1M12 8V7m0 1v8m0 0v1m0-1c-1.11 0-2.08-.402-2.599-1M21 12a9 9 0 11-18 0 9 9 0 0118 0z" },
] as const;

export default function DashboardPage() {
  const [activeTab, setActiveTab] = useState("shadow");

  return (
    <div className="min-h-screen bg-dark-950">
      {/* Header */}
      <header className="border-b border-dark-800 bg-dark-900/80 backdrop-blur-sm sticky top-0 z-30">
        <div className="max-w-7xl mx-auto px-6">
          <div className="flex items-center justify-between py-3">
            <div className="flex items-center gap-3">
              <div className="h-8 w-8 rounded-lg bg-accent-blue flex items-center justify-center text-white font-bold text-sm">
                AB
              </div>
              <h1 className="text-lg font-semibold text-dark-100">AgentBot</h1>
            </div>
            <p className="text-xs text-dark-500 hidden sm:block">Polymarket Trading Engine</p>
          </div>

          {/* Tab bar */}
          <nav className="flex gap-1 -mb-px">
            {TABS.map((tab) => {
              const isActive = activeTab === tab.id;
              const isDisabled = tab.id === "real";
              return (
                <button
                  key={tab.id}
                  onClick={() => setActiveTab(tab.id)}
                  className={`flex items-center gap-2 px-4 py-2.5 text-sm font-medium transition-all border-b-2 ${
                    isActive
                      ? "border-accent-blue text-dark-100"
                      : "border-transparent text-dark-500 hover:text-dark-300 hover:border-dark-600"
                  } ${isDisabled ? "opacity-60" : ""}`}
                >
                  <svg className="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24" strokeWidth={1.5}>
                    <path strokeLinecap="round" strokeLinejoin="round" d={tab.icon} />
                  </svg>
                  {tab.label}
                  {isDisabled && (
                    <span className="px-1.5 py-0.5 rounded text-[9px] bg-dark-700 text-dark-500 font-normal">OFF</span>
                  )}
                </button>
              );
            })}
          </nav>
        </div>
      </header>

      {/* Content */}
      <main className="max-w-7xl mx-auto px-6 py-6">
        {activeTab === "simulation" && <SimulationTab />}
        {activeTab === "shadow" && <ShadowTab />}
        {activeTab === "real" && <RealTradingTab />}
      </main>
    </div>
  );
}
