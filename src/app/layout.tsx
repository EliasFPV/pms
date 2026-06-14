import type { Metadata } from "next";
import Link from "next/link";
import "./globals.css";

export const metadata: Metadata = {
  title: "PMS Italia – Verwaltung Ferienwohnungen",
  description: "Property Management System für 5 Ferienwohnungen in Italien",
};

const nav = [
  { href: "/", label: "Dashboard" },
  { href: "/calendar", label: "Kalender" },
];

export default function RootLayout({ children }: { children: React.ReactNode }) {
  return (
    <html lang="de">
      <body>
        <div className="min-h-screen">
          <header className="border-b border-slate-200 bg-white">
            <div className="mx-auto flex max-w-7xl items-center justify-between px-6 py-3">
              <div className="flex items-center gap-2">
                <span className="text-xl">🏖️</span>
                <span className="text-lg font-semibold text-slate-800">PMS Italia</span>
              </div>
              <nav className="flex gap-1">
                {nav.map((n) => (
                  <Link
                    key={n.href}
                    href={n.href as any}
                    className="rounded-md px-3 py-1.5 text-sm font-medium text-slate-600 hover:bg-slate-100 hover:text-slate-900"
                  >
                    {n.label}
                  </Link>
                ))}
              </nav>
            </div>
          </header>
          <main className="mx-auto max-w-7xl px-6 py-6">{children}</main>
        </div>
      </body>
    </html>
  );
}
