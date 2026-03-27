import StatusCard from "@/components/StatusCard";
import OrdersTable from "@/components/OrdersTable";
import InventoryPanel from "@/components/InventoryPanel";
import PnlPanel from "@/components/PnlPanel";
import MarketTable from "@/components/MarketTable";
import ControlsPanel from "@/components/ControlsPanel";
import QuickStats from "@/components/QuickStats";
import BacktestPanel from "@/components/BacktestPanel";
import ShadowPanel from "@/components/ShadowPanel";

export default function DashboardPage() {
  return (
    <div className="min-h-screen bg-dark-950">
      <header className="border-b border-dark-800 bg-dark-900/80 backdrop-blur-sm sticky top-0 z-10">
        <div className="max-w-7xl mx-auto px-6 py-4 flex items-center justify-between">
          <div className="flex items-center gap-3">
            <div className="h-8 w-8 rounded-lg bg-accent-blue flex items-center justify-center text-white font-bold text-sm">
              AB
            </div>
            <h1 className="text-lg font-semibold text-dark-100">AgentBot Dashboard</h1>
          </div>
          <p className="text-xs text-dark-500">Polymarket Trading Engine</p>
        </div>
      </header>

      <main className="max-w-7xl mx-auto px-6 py-6">
        <div className="grid grid-cols-1 lg:grid-cols-3 gap-5 mb-5">
          <StatusCard />
          <ControlsPanel />
          <InventoryPanel />
        </div>

        <div className="grid grid-cols-1 lg:grid-cols-2 gap-5 mb-5">
          <PnlPanel />
          <QuickStats />
        </div>

        <div className="grid grid-cols-1 gap-5 mb-5">
          <OrdersTable />
        </div>

        <div className="grid grid-cols-1 gap-5 mb-5">
          <MarketTable />
        </div>

        <div className="grid grid-cols-1 gap-5 mb-5">
          <ShadowPanel />
        </div>

        <div className="grid grid-cols-1 gap-5">
          <BacktestPanel />
        </div>
      </main>
    </div>
  );
}
