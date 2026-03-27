"use client";

import { useState, useRef, useEffect } from "react";

const GLOSSARY: Record<string, string> = {
  "Edge": "Diferencia entre el precio justo estimado y el precio de mercado. Mayor edge = mayor oportunidad teorica de beneficio.",
  "Reward Efficiency": "Ratio de recompensa por liquidez respecto al capital desplegado. Mide cuanto recibes del programa de incentivos de Polymarket.",
  "Competition Density": "Porcentaje de competidores activos en el libro de ordenes. Alta densidad = dificil obtener fills.",
  "Spread": "Diferencia entre mejor bid y mejor ask. Spread bajo = mercado liquido pero menos margen por operacion.",
  "Volatility Penalty": "Factor de penalizacion por volatilidad del mercado. Mercados volatiles tienen sizing reducido.",
  "Regime": "Clasificacion de volatilidad: CALM (estable), NORMAL, VOLATILE (riesgoso), CRISIS (bloqueado).",
  "Hyp PnL": "Hypothetical PnL: beneficio/perdida que HABRIA obtenido el shadow engine si hubiera operado con dinero real.",
  "Toxic Fill": "Fill donde el precio se movio en contra inmediatamente despues. Indica seleccion adversa por flujo informado.",
  "Fill Rate": "Porcentaje de ordenes colocadas que reciben ejecucion. Bajo = ordenes demasiado alejadas del mercado.",
  "Max Drawdown": "Mayor caida desde un pico de PnL. Indica el peor escenario historico de perdida acumulada.",
  "Max Net Exposure": "Mayor desequilibrio neto entre posiciones YES y NO. Exposicion alta = mas riesgo direccional.",
  "Capital Share": "Porcentaje del presupuesto total asignado a un mercado. Hard cap al 25% por defecto.",
  "Cooldown": "Periodo de pausa tras un toxic fill. El mercado no recibe nuevas ordenes durante N ciclos.",
};

export default function InfoTooltip({ term }: { term: string }) {
  const [show, setShow] = useState(false);
  const ref = useRef<HTMLDivElement>(null);
  const text = GLOSSARY[term] ?? term;

  useEffect(() => {
    if (!show) return;
    const handler = (e: MouseEvent) => {
      if (ref.current && !ref.current.contains(e.target as Node)) setShow(false);
    };
    document.addEventListener("mousedown", handler);
    return () => document.removeEventListener("mousedown", handler);
  }, [show]);

  return (
    <span className="relative inline-flex items-center ml-1" ref={ref}>
      <button
        onClick={() => setShow(!show)}
        onMouseEnter={() => setShow(true)}
        onMouseLeave={() => setShow(false)}
        className="w-3.5 h-3.5 rounded-full bg-dark-700 text-dark-400 text-[9px] font-bold flex items-center justify-center hover:bg-dark-600 hover:text-dark-200 transition-colors cursor-help"
        aria-label={`Info: ${term}`}
      >
        i
      </button>
      {show && (
        <div className="absolute bottom-full left-1/2 -translate-x-1/2 mb-2 z-50 w-64 px-3 py-2 rounded-lg bg-dark-700 border border-dark-600 text-xs text-dark-200 shadow-xl leading-relaxed">
          <div className="font-semibold text-dark-100 mb-0.5">{term}</div>
          {text}
          <div className="absolute top-full left-1/2 -translate-x-1/2 w-2 h-2 bg-dark-700 border-r border-b border-dark-600 transform rotate-45 -mt-1" />
        </div>
      )}
    </span>
  );
}
