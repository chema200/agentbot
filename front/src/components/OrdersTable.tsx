"use client";

import { useState } from "react";
import { useApi } from "@/hooks/useApi";
import { api, Order } from "@/lib/api";
import OrderDetailModal from "./OrderDetailModal";

type SortKey = keyof Order;
type SortDir = "asc" | "desc";

export default function OrdersTable() {
  const { data: orders, loading } = useApi<Order[]>(api.getOrders);
  const [sortKey, setSortKey] = useState<SortKey>("id");
  const [sortDir, setSortDir] = useState<SortDir>("desc");
  const [selectedOrderId, setSelectedOrderId] = useState<string | null>(null);

  const handleSort = (key: SortKey) => {
    if (sortKey === key) {
      setSortDir(sortDir === "asc" ? "desc" : "asc");
    } else {
      setSortKey(key);
      setSortDir("asc");
    }
  };

  const sorted = orders
    ? [...orders].sort((a, b) => {
        const aVal = a[sortKey];
        const bVal = b[sortKey];
        if (aVal == null || bVal == null) return 0;
        const cmp = aVal < bVal ? -1 : aVal > bVal ? 1 : 0;
        return sortDir === "asc" ? cmp : -cmp;
      })
    : [];

  if (loading) return <TableSkeleton />;

  return (
    <>
      <div className="card overflow-hidden">
        <h2 className="text-sm font-medium text-dark-400 uppercase tracking-wider mb-4">
          Orders
        </h2>
        <div className="overflow-x-auto">
          <table className="w-full text-sm">
            <thead>
              <tr className="border-b border-dark-700">
                {(["id", "market", "side", "price", "size", "status"] as SortKey[]).map((key) => (
                  <th
                    key={key}
                    onClick={() => handleSort(key)}
                    className="text-left py-2 px-3 text-dark-400 font-medium cursor-pointer hover:text-dark-200 transition-colors select-none"
                  >
                    {key.charAt(0).toUpperCase() + key.slice(1)}
                    {sortKey === key && (
                      <span className="ml-1">{sortDir === "asc" ? "\u2191" : "\u2193"}</span>
                    )}
                  </th>
                ))}
              </tr>
            </thead>
            <tbody>
              {sorted.map((order) => (
                <tr
                  key={order.orderId ?? order.id}
                  onClick={() => order.orderId && setSelectedOrderId(order.orderId)}
                  className="border-b border-dark-800 hover:bg-dark-800/50 transition-colors cursor-pointer group"
                >
                  <td className="py-2.5 px-3 text-dark-300 font-mono text-xs group-hover:text-accent-blue transition-colors">
                    #{order.id}
                  </td>
                  <td className="py-2.5 px-3 max-w-[200px] truncate">{order.market}</td>
                  <td className="py-2.5 px-3">
                    <span className={order.side === "BUY" ? "badge-green" : "badge-red"}>
                      {order.side}
                    </span>
                  </td>
                  <td className="py-2.5 px-3 font-mono">${order.price}</td>
                  <td className="py-2.5 px-3 font-mono">{order.size}</td>
                  <td className="py-2.5 px-3">
                    <StatusBadge status={order.status} />
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </div>

      <OrderDetailModal
        orderId={selectedOrderId}
        onClose={() => setSelectedOrderId(null)}
      />
    </>
  );
}

function StatusBadge({ status }: { status: string }) {
  const cls =
    status === "OPEN"
      ? "badge-blue"
      : status === "FILLED"
        ? "badge-green"
        : "badge-yellow";
  return <span className={cls}>{status}</span>;
}

function TableSkeleton() {
  return (
    <div className="card animate-pulse">
      <div className="h-4 w-20 bg-dark-700 rounded mb-4" />
      {[...Array(4)].map((_, i) => (
        <div key={i} className="h-8 bg-dark-800 rounded mb-2" />
      ))}
    </div>
  );
}
