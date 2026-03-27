"use client";

import StatusCard from "./StatusCard";
import ControlsPanel from "./ControlsPanel";
import InventoryPanel from "./InventoryPanel";
import PnlPanel from "./PnlPanel";
import QuickStats from "./QuickStats";
import OrdersTable from "./OrdersTable";
import MarketTable from "./MarketTable";
import BacktestPanel from "./BacktestPanel";
import HelpSection from "./HelpSection";

export default function SimulationTab() {
  return (
    <div className="space-y-5">
      <HelpSection
        title="Como leer esta pantalla"
        items={[
          "El motor de simulacion opera mercados internos con regimenes de volatilidad configurables (CALM, NORMAL, VOLATILE, CRISIS).",
          "Las ordenes, fills y PnL son del motor simulado. No hay dinero real involucrado.",
          "Los mercados se seleccionan por edge score y se filtran por regimen y risk limits.",
          "La seccion 'Validation Framework' permite ejecutar backtests y Monte Carlo para validar la estrategia.",
          "Usa esta vista para iterar y mejorar la estrategia antes de pasar a Shadow Polymarket.",
        ]}
      />

      <div className="grid grid-cols-1 lg:grid-cols-3 gap-5">
        <StatusCard />
        <ControlsPanel />
        <InventoryPanel />
      </div>

      <div className="grid grid-cols-1 lg:grid-cols-2 gap-5">
        <PnlPanel />
        <QuickStats />
      </div>

      <OrdersTable />
      <MarketTable />
      <BacktestPanel />
    </div>
  );
}
