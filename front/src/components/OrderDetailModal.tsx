"use client";

import { useEffect, useState, useCallback } from "react";
import { api, OrderDetail } from "@/lib/api";

interface Props {
  orderId: string | null;
  onClose: () => void;
}

export default function OrderDetailModal({ orderId, onClose }: Props) {
  const [detail, setDetail] = useState<OrderDetail | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    if (!orderId) return;
    setLoading(true);
    setError(null);
    api.getOrderDetail(orderId)
      .then(setDetail)
      .catch(() => setError("Failed to load order detail"))
      .finally(() => setLoading(false));
  }, [orderId]);

  const handleBackdropClick = useCallback((e: React.MouseEvent) => {
    if (e.target === e.currentTarget) onClose();
  }, [onClose]);

  useEffect(() => {
    const handleEsc = (e: KeyboardEvent) => { if (e.key === "Escape") onClose(); };
    window.addEventListener("keydown", handleEsc);
    return () => window.removeEventListener("keydown", handleEsc);
  }, [onClose]);

  if (!orderId) return null;

  return (
    <div
      className="fixed inset-0 z-50 flex items-start justify-end bg-black/60 backdrop-blur-sm"
      onClick={handleBackdropClick}
    >
      <div className="h-full w-full max-w-lg overflow-y-auto bg-dark-900 border-l border-dark-700 shadow-2xl">
        <div className="sticky top-0 z-10 bg-dark-900/95 backdrop-blur-sm border-b border-dark-700 px-5 py-3 flex items-center justify-between">
          <h2 className="text-sm font-semibold text-dark-200">Order Detail</h2>
          <button
            onClick={onClose}
            className="text-dark-400 hover:text-dark-200 transition-colors text-lg leading-none px-1"
          >
            &times;
          </button>
        </div>

        <div className="p-5 space-y-5">
          {loading && <LoadingSkeleton />}
          {error && <div className="text-accent-red text-sm">{error}</div>}
          {detail && !loading && (
            <>
              <OrderSection d={detail} />
              <ExecutionSection fills={detail.fills} />
              <SnapshotSection s={detail.marketSnapshot} />
              <SummarySection s={detail.marketSummary} marketName={detail.marketName} />
            </>
          )}
        </div>
      </div>
    </div>
  );
}

function OrderSection({ d }: { d: OrderDetail }) {
  return (
    <section>
      <SectionTitle>Order</SectionTitle>
      <div className="grid grid-cols-2 gap-x-4 gap-y-2">
        <Field label="Order ID" value={d.orderId} mono />
        <Field label="Market" value={d.marketName} />
        <Field label="Market ID" value={d.marketId} mono small />
        <Field label="Side">
          <Badge className={d.side === "BUY" ? "badge-green" : "badge-red"}>{d.side}</Badge>
        </Field>
        <Field label="Price" value={`$${d.price}`} mono />
        <Field label="Size" value={`${d.originalSize}`} mono />
        <Field label="Status">
          <StatusBadge status={d.status} />
        </Field>
        <Field label="Age" value={formatAge(d.ageSeconds)} />
        <Field label="Filled" value={`${d.filledSize} / ${d.originalSize}`} mono />
        <Field label="Remaining" value={`${d.remainingSize}`} mono />
        <Field label="Queue Ahead" value={`${d.queueAhead}`} mono />
        <Field label="Queue Position" value={`${d.queuePosition}`} mono />
        {d.visibleAfter && (
          <Field label="Visible After" value={new Date(d.visibleAfter).toLocaleTimeString()} mono small />
        )}
        {d.lastActionReason && (
          <Field label="Last Action" value={d.lastActionReason} className="col-span-2" small />
        )}
      </div>
    </section>
  );
}

function ExecutionSection({ fills }: { fills: OrderDetail["fills"] }) {
  if (!fills || fills.length === 0) {
    return (
      <section>
        <SectionTitle>Execution</SectionTitle>
        <div className="text-dark-500 text-xs">No fills for this order</div>
      </section>
    );
  }

  return (
    <section>
      <SectionTitle>Execution ({fills.length} fill{fills.length > 1 ? "s" : ""})</SectionTitle>
      <div className="space-y-2">
        {fills.map((f) => (
          <div key={f.fillId} className="bg-dark-800 rounded-lg p-3 text-xs space-y-1.5">
            <div className="flex items-center justify-between">
              <span className="font-mono text-dark-400">{f.fillId}</span>
              <div className="flex items-center gap-2">
                {f.toxicFlow && <Badge className="bg-accent-red/20 text-accent-red border border-accent-red/30">TOXIC</Badge>}
                <Badge className={f.side === "BUY" ? "badge-green" : "badge-red"}>{f.side}</Badge>
              </div>
            </div>
            <div className="grid grid-cols-3 gap-2">
              <MiniField label="Price" value={`$${f.fillPrice}`} />
              <MiniField label="Size" value={`${f.fillSize}`} />
              <MiniField label="Fee" value={`$${f.fee.toFixed(4)}`} />
              <MiniField label="Slippage" value={f.slippage.toFixed(4)} color={f.slippage > 0 ? "red" : "green"} />
              <MiniField label="Mid @ Fill" value={`$${f.midAtFill}`} />
              <MiniField label="Est. PnL" value={f.estimatedPnl.toFixed(4)} color={f.estimatedPnl >= 0 ? "green" : "red"} />
            </div>
          </div>
        ))}
      </div>
    </section>
  );
}

function SnapshotSection({ s }: { s: OrderDetail["marketSnapshot"] }) {
  if (!s) return null;
  return (
    <section>
      <SectionTitle>Market Snapshot at Creation</SectionTitle>
      <div className="grid grid-cols-2 gap-x-4 gap-y-2">
        <Field label="Edge Score" value={s.edgeScore?.toFixed(4) ?? "-"} mono />
        <Field label="Reward Eff." value={s.rewardEfficiency?.toFixed(6) ?? "-"} mono />
        <Field label="Comp. Density" value={s.competitionDensity?.toFixed(4) ?? "-"} mono />
        <Field label="Vol. Penalty" value={s.volatilityPenalty?.toFixed(4) ?? "-"} mono />
        <Field label="Capital Share" value={s.capitalShare ? `${(s.capitalShare * 100).toFixed(1)}%` : "-"} mono />
        <Field label="Regime">
          <RegimeBadge regime={s.regime} />
        </Field>
        <Field label="Spread" value={s.spread?.toFixed(4) ?? "-"} mono />
        <Field label="Mid" value={s.mid ? `$${s.mid}` : "-"} mono />
        <Field label="Best Bid" value={s.bestBid ? `$${s.bestBid}` : "-"} mono />
        <Field label="Best Ask" value={s.bestAsk ? `$${s.bestAsk}` : "-"} mono />
      </div>
    </section>
  );
}

function SummarySection({ s, marketName }: { s: OrderDetail["marketSummary"]; marketName: string }) {
  if (!s) return null;
  return (
    <section>
      <SectionTitle>Market Summary &mdash; {marketName}</SectionTitle>
      <div className="grid grid-cols-2 gap-x-4 gap-y-2">
        <Field label="Total Fills" value={`${s.totalFills}`} mono />
        <Field label="Active Orders" value={`${s.activeOrders}`} mono />
        <Field label="Trading PnL" mono>
          <span className={s.tradingPnl >= 0 ? "text-accent-green" : "text-accent-red"}>
            {s.tradingPnl.toFixed(4)}
          </span>
        </Field>
        <Field label="Reward PnL" value={s.rewardPnl.toFixed(4)} mono />
        <Field label="Net Exposure" value={s.netExposure.toFixed(2)} mono className="col-span-2" />
      </div>
    </section>
  );
}

function SectionTitle({ children }: { children: React.ReactNode }) {
  return (
    <h3 className="text-xs font-medium text-dark-400 uppercase tracking-wider mb-3 pb-1 border-b border-dark-800">
      {children}
    </h3>
  );
}

function Field({ label, value, children, mono, small, className, color }: {
  label: string; value?: string; children?: React.ReactNode;
  mono?: boolean; small?: boolean; className?: string; color?: string;
}) {
  const colorCls = color === "green" ? "text-accent-green" : color === "red" ? "text-accent-red" : "text-dark-200";
  return (
    <div className={className}>
      <div className="text-[10px] text-dark-500 uppercase">{label}</div>
      {children ?? (
        <div className={`${small ? "text-xs" : "text-sm"} ${mono ? "font-mono" : ""} ${colorCls}`}>
          {value}
        </div>
      )}
    </div>
  );
}

function MiniField({ label, value, color }: { label: string; value: string; color?: string }) {
  const colorCls = color === "green" ? "text-accent-green" : color === "red" ? "text-accent-red" : "text-dark-300";
  return (
    <div>
      <div className="text-[9px] text-dark-500 uppercase">{label}</div>
      <div className={`text-xs font-mono ${colorCls}`}>{value}</div>
    </div>
  );
}

function Badge({ children, className }: { children: React.ReactNode; className?: string }) {
  return <span className={`text-[10px] px-1.5 py-0.5 rounded font-medium ${className ?? ""}`}>{children}</span>;
}

function StatusBadge({ status }: { status: string }) {
  const cls = status === "OPEN" ? "badge-blue"
    : status === "PARTIALLY_FILLED" ? "bg-yellow-500/20 text-yellow-400 border border-yellow-500/30"
    : status === "FILLED" ? "badge-green"
    : "badge-yellow";
  return <Badge className={cls}>{status}</Badge>;
}

function RegimeBadge({ regime }: { regime: string | null }) {
  if (!regime) return <span className="text-dark-500 text-xs">-</span>;
  const cls = regime === "CALM" ? "bg-emerald-500/20 text-emerald-400"
    : regime === "NORMAL" ? "bg-blue-500/20 text-blue-400"
    : regime === "VOLATILE" ? "bg-orange-500/20 text-orange-400"
    : "bg-red-500/20 text-red-400";
  return <Badge className={cls}>{regime}</Badge>;
}

function formatAge(seconds: number): string {
  if (seconds < 60) return `${seconds}s`;
  if (seconds < 3600) return `${Math.floor(seconds / 60)}m ${seconds % 60}s`;
  return `${Math.floor(seconds / 3600)}h ${Math.floor((seconds % 3600) / 60)}m`;
}

function LoadingSkeleton() {
  return (
    <div className="animate-pulse space-y-4">
      {[...Array(4)].map((_, i) => (
        <div key={i}>
          <div className="h-3 w-24 bg-dark-700 rounded mb-3" />
          <div className="grid grid-cols-2 gap-2">
            {[...Array(4)].map((_, j) => (
              <div key={j} className="h-8 bg-dark-800 rounded" />
            ))}
          </div>
        </div>
      ))}
    </div>
  );
}
