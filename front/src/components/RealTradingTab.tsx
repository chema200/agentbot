"use client";

import HelpSection from "./HelpSection";

export default function RealTradingTab() {
  return (
    <div className="space-y-5">
      <HelpSection
        title="Como leer esta pantalla"
        items={[
          "Este modo ejecutaria operaciones con dinero real en Polymarket.",
          "Actualmente DESACTIVADO. Ninguna operacion se envia al mercado.",
          "Antes de activarlo, asegurate de que Shadow Polymarket muestra resultados consistentes y rentables.",
          "Los risk limits se aplicarian igual que en Shadow, pero con ejecucion real via CLOB API.",
          "El kill switch detendria inmediatamente toda actividad y cancelaria ordenes abiertas.",
        ]}
      />

      {/* Disabled banner */}
      <div className="bg-accent-red/10 border border-accent-red/30 rounded-xl p-6 text-center">
        <div className="flex items-center justify-center gap-3 mb-3">
          <div className="w-4 h-4 rounded-full bg-accent-red/40 flex items-center justify-center">
            <div className="w-2 h-2 rounded-full bg-accent-red" />
          </div>
          <h2 className="text-lg font-semibold text-accent-red">MODO DESACTIVADO</h2>
        </div>
        <p className="text-sm text-dark-400 max-w-md mx-auto">
          El trading real no esta activado. Ninguna operacion se ejecuta con dinero real.
          Valida primero los resultados en Shadow Polymarket antes de considerar la activacion.
        </p>
      </div>

      {/* Config cards */}
      <div className="grid grid-cols-1 lg:grid-cols-2 gap-5">
        {/* Risk Limits */}
        <div className="card">
          <h3 className="text-sm font-medium text-dark-400 uppercase tracking-wider mb-4">Risk Limits</h3>
          <div className="space-y-3">
            <ConfigRow label="Max Capital Share / Market" value="25%" />
            <ConfigRow label="Max YES Exposure" value="$500.00" />
            <ConfigRow label="Max NO Exposure" value="$500.00" />
            <ConfigRow label="Max Net Exposure" value="$300.00" />
            <ConfigRow label="Cooldown Cycles (toxic fill)" value="15" />
            <ConfigRow label="Block Volatile Markets" value="No" />
            <ConfigRow label="Min Edge After Penalty" value="0.30" />
          </div>
        </div>

        {/* Execution config */}
        <div className="card">
          <h3 className="text-sm font-medium text-dark-400 uppercase tracking-wider mb-4">Execution Config</h3>
          <div className="space-y-3">
            <ConfigRow label="Max Order Size" value="$50.00" />
            <ConfigRow label="Min Order Size" value="$5.00" />
            <ConfigRow label="Quote Aggressiveness" value="0.50" />
            <ConfigRow label="Regime Penalty (Volatile)" value="0.35x" />
            <ConfigRow label="Regime Penalty (Crisis)" value="0.00x (blocked)" />
            <ConfigRow label="Inventory Penalty K" value="0.50" />
          </div>
        </div>

        {/* Wallet status */}
        <div className="card">
          <h3 className="text-sm font-medium text-dark-400 uppercase tracking-wider mb-4">Wallet / Credentials</h3>
          <div className="space-y-3">
            <ConfigRow label="API Key" value="No configurada" status="missing" />
            <ConfigRow label="API Secret" value="No configurada" status="missing" />
            <ConfigRow label="Wallet Address" value="No configurada" status="missing" />
            <ConfigRow label="USDC Balance" value="-" status="missing" />
            <ConfigRow label="Allowance" value="-" status="missing" />
          </div>
        </div>

        {/* Kill switch */}
        <div className="card">
          <h3 className="text-sm font-medium text-dark-400 uppercase tracking-wider mb-4">Kill Switch</h3>
          <div className="flex flex-col items-center justify-center py-6">
            <button
              disabled
              className="w-32 h-32 rounded-full border-4 border-dark-700 bg-dark-800 text-dark-600 flex flex-col items-center justify-center cursor-not-allowed opacity-50"
            >
              <svg className="w-10 h-10 mb-1" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M18.364 18.364A9 9 0 005.636 5.636m12.728 12.728A9 9 0 015.636 5.636m12.728 12.728L5.636 5.636" />
              </svg>
              <span className="text-xs font-medium">KILL</span>
            </button>
            <p className="text-xs text-dark-500 mt-4 text-center max-w-xs">
              Detiene inmediatamente toda actividad y cancela ordenes abiertas. No disponible hasta activar el modo real.
            </p>
          </div>
        </div>
      </div>
    </div>
  );
}

function ConfigRow({ label, value, status }: { label: string; value: string; status?: string }) {
  const valueCls = status === "missing"
    ? "text-dark-600"
    : "text-dark-200 font-mono";

  return (
    <div className="flex items-center justify-between py-1.5 border-b border-dark-800/50">
      <span className="text-xs text-dark-400">{label}</span>
      <span className={`text-xs ${valueCls}`}>{value}</span>
    </div>
  );
}
