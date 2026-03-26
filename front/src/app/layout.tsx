import type { Metadata } from "next";
import "./globals.css";

export const metadata: Metadata = {
  title: "AgentBot Dashboard",
  description: "Polymarket Trading Dashboard",
};

export default function RootLayout({
  children,
}: Readonly<{
  children: React.ReactNode;
}>) {
  return (
    <html lang="en" className="dark">
      <body className="bg-dark-950 text-dark-100 antialiased">{children}</body>
    </html>
  );
}
