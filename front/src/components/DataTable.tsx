"use client";

import { useState, useMemo } from "react";

interface Column<T> {
  key: string;
  label: string;
  render: (row: T) => React.ReactNode;
  sortable?: boolean;
  sortValue?: (row: T) => number | string;
  align?: "left" | "right" | "center";
  className?: string;
}

interface DataTableProps<T> {
  columns: Column<T>[];
  data: T[];
  rowKey: (row: T) => string;
  emptyMessage?: string;
  searchPlaceholder?: string;
  searchFilter?: (row: T, query: string) => boolean;
  onRowClick?: (row: T) => void;
  pageSizes?: number[];
  defaultPageSize?: number;
  stickyHeader?: boolean;
  maxHeight?: string;
}

export default function DataTable<T>({
  columns,
  data,
  rowKey,
  emptyMessage = "No hay datos",
  searchPlaceholder = "Buscar...",
  searchFilter,
  onRowClick,
  pageSizes = [25, 50, 100],
  defaultPageSize = 25,
  stickyHeader = true,
  maxHeight,
}: DataTableProps<T>) {
  const [search, setSearch] = useState("");
  const [sortKey, setSortKey] = useState<string | null>(null);
  const [sortDir, setSortDir] = useState<"asc" | "desc">("asc");
  const [page, setPage] = useState(0);
  const [pageSize, setPageSize] = useState(defaultPageSize);

  const filtered = useMemo(() => {
    if (!search || !searchFilter) return data;
    const q = search.toLowerCase();
    return data.filter((row) => searchFilter(row, q));
  }, [data, search, searchFilter]);

  const sorted = useMemo(() => {
    if (!sortKey) return filtered;
    const col = columns.find((c) => c.key === sortKey);
    if (!col?.sortValue) return filtered;
    return [...filtered].sort((a, b) => {
      const av = col.sortValue!(a);
      const bv = col.sortValue!(b);
      const cmp = av < bv ? -1 : av > bv ? 1 : 0;
      return sortDir === "asc" ? cmp : -cmp;
    });
  }, [filtered, sortKey, sortDir, columns]);

  const totalPages = Math.max(1, Math.ceil(sorted.length / pageSize));
  const safePage = Math.min(page, totalPages - 1);
  const paged = sorted.slice(safePage * pageSize, (safePage + 1) * pageSize);

  const handleSort = (key: string) => {
    if (sortKey === key) {
      setSortDir(sortDir === "asc" ? "desc" : "asc");
    } else {
      setSortKey(key);
      setSortDir("asc");
    }
  };

  if (data.length === 0) {
    return (
      <div className="text-dark-500 text-sm text-center py-10 bg-dark-800/30 rounded-lg border border-dark-800">
        {emptyMessage}
      </div>
    );
  }

  return (
    <div>
      {searchFilter && (
        <div className="flex items-center gap-3 mb-3">
          <input
            type="text"
            value={search}
            onChange={(e) => { setSearch(e.target.value); setPage(0); }}
            placeholder={searchPlaceholder}
            className="flex-1 max-w-xs px-3 py-1.5 rounded-lg bg-dark-800 border border-dark-700 text-sm text-dark-200 placeholder-dark-500 focus:outline-none focus:border-dark-500"
          />
          <span className="text-xs text-dark-500">{filtered.length} resultados</span>
        </div>
      )}

      <div className={`overflow-x-auto ${maxHeight ? `max-h-[${maxHeight}] overflow-y-auto` : ""}`} style={maxHeight ? { maxHeight } : undefined}>
        <table className="w-full text-xs">
          <thead className={stickyHeader ? "sticky top-0 z-10 bg-dark-900" : ""}>
            <tr className="border-b border-dark-700">
              {columns.map((col) => (
                <th
                  key={col.key}
                  onClick={col.sortable ? () => handleSort(col.key) : undefined}
                  className={`py-2 px-2 font-medium text-dark-400 ${
                    col.align === "right" ? "text-right" : col.align === "center" ? "text-center" : "text-left"
                  } ${col.sortable ? "cursor-pointer hover:text-dark-200 select-none" : ""} ${col.className ?? ""}`}
                >
                  {col.label}
                  {sortKey === col.key && (
                    <span className="ml-1">{sortDir === "asc" ? "\u2191" : "\u2193"}</span>
                  )}
                </th>
              ))}
            </tr>
          </thead>
          <tbody>
            {paged.map((row) => (
              <tr
                key={rowKey(row)}
                onClick={onRowClick ? () => onRowClick(row) : undefined}
                className={`border-b border-dark-800 hover:bg-dark-800/50 transition-colors ${onRowClick ? "cursor-pointer" : ""}`}
              >
                {columns.map((col) => (
                  <td
                    key={col.key}
                    className={`py-1.5 px-2 ${
                      col.align === "right" ? "text-right" : col.align === "center" ? "text-center" : "text-left"
                    }`}
                  >
                    {col.render(row)}
                  </td>
                ))}
              </tr>
            ))}
          </tbody>
        </table>
      </div>

      {sorted.length > pageSizes[0] && (
        <div className="flex items-center justify-between mt-3 text-xs text-dark-400">
          <div className="flex items-center gap-2">
            <span>Por pagina:</span>
            {pageSizes.map((s) => (
              <button
                key={s}
                onClick={() => { setPageSize(s); setPage(0); }}
                className={`px-2 py-0.5 rounded ${pageSize === s ? "bg-dark-700 text-dark-200" : "hover:text-dark-200"}`}
              >
                {s}
              </button>
            ))}
          </div>
          <div className="flex items-center gap-2">
            <button onClick={() => setPage(Math.max(0, safePage - 1))} disabled={safePage === 0} className="px-2 py-0.5 rounded hover:bg-dark-700 disabled:opacity-30">
              Ant
            </button>
            <span>{safePage + 1} / {totalPages}</span>
            <button onClick={() => setPage(Math.min(totalPages - 1, safePage + 1))} disabled={safePage >= totalPages - 1} className="px-2 py-0.5 rounded hover:bg-dark-700 disabled:opacity-30">
              Sig
            </button>
          </div>
        </div>
      )}
    </div>
  );
}
