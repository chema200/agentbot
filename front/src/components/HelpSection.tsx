"use client";

import { useState } from "react";

export default function HelpSection({ title, items }: { title: string; items: string[] }) {
  const [open, setOpen] = useState(false);

  return (
    <div className="mb-4">
      <button
        onClick={() => setOpen(!open)}
        className="flex items-center gap-2 text-xs text-dark-500 hover:text-dark-300 transition-colors"
      >
        <svg className={`w-3 h-3 transition-transform ${open ? "rotate-90" : ""}`} fill="currentColor" viewBox="0 0 20 20">
          <path d="M6 4l8 6-8 6V4z" />
        </svg>
        {title}
      </button>
      {open && (
        <div className="mt-2 ml-5 space-y-1">
          {items.map((item, i) => (
            <div key={i} className="flex items-start gap-2 text-xs text-dark-400">
              <span className="text-dark-600 mt-0.5">-</span>
              <span>{item}</span>
            </div>
          ))}
        </div>
      )}
    </div>
  );
}
