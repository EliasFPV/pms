import { prisma } from "@/lib/prisma";
import { format } from "date-fns";

export const dynamic = "force-dynamic";

async function getData() {
  const today = new Date();
  const dayStart = new Date(Date.UTC(today.getFullYear(), today.getMonth(), today.getDate()));
  const dayEnd = new Date(dayStart);
  dayEnd.setUTCDate(dayEnd.getUTCDate() + 1);

  const [units, checkIns, checkOuts, openTasks, pendingAlloggiati] = await Promise.all([
    prisma.unit.findMany({
      where: { active: true },
      orderBy: { name: "asc" },
      include: {
        bookings: {
          where: { status: { not: "CANCELLED" }, checkIn: { lt: dayEnd }, checkOut: { gt: dayStart } },
          include: { guests: { include: { guest: true } } },
        },
      },
    }),
    prisma.booking.findMany({
      where: { checkIn: { gte: dayStart, lt: dayEnd }, status: { not: "CANCELLED" } },
      include: { unit: true, guests: { include: { guest: true } } },
    }),
    prisma.booking.findMany({
      where: { checkOut: { gte: dayStart, lt: dayEnd }, status: { not: "CANCELLED" } },
      include: { unit: true, guests: { include: { guest: true } } },
    }),
    prisma.task.findMany({ where: { status: { not: "DONE" } }, orderBy: { dueDate: "asc" }, take: 10 }),
    prisma.booking.count({
      where: { checkIn: { gte: dayStart, lt: dayEnd }, alloggiatiSentAt: null, status: { not: "CANCELLED" } },
    }),
  ]);

  const occupied = units.filter((u) => u.bookings.length > 0).length;
  return { units, checkIns, checkOuts, openTasks, pendingAlloggiati, occupied, dayStart };
}

function Kpi({ label, value, accent }: { label: string; value: string | number; accent?: string }) {
  return (
    <div className="rounded-xl border border-slate-200 bg-white p-4 shadow-sm">
      <div className="text-sm text-slate-500">{label}</div>
      <div className={`mt-1 text-2xl font-semibold ${accent ?? "text-slate-800"}`}>{value}</div>
    </div>
  );
}

export default async function DashboardPage() {
  const d = await getData();
  const fmt = (x: Date) => format(new Date(x), "dd.MM.yyyy");

  return (
    <div className="space-y-6">
      <div className="flex items-center justify-between">
        <div>
          <h1 className="text-2xl font-bold text-slate-800">Dashboard</h1>
          <p className="text-sm text-slate-500">{format(d.dayStart, "EEEE, dd. MMMM yyyy")}</p>
        </div>
        <a
          href={`/api/alloggiati?date=${format(d.dayStart, "yyyy-MM-dd")}`}
          className="rounded-lg bg-brand-600 px-4 py-2 text-sm font-medium text-white hover:bg-brand-700"
        >
          Alloggiati .txt (heute)
        </a>
      </div>

      {/* KPIs */}
      <div className="grid grid-cols-2 gap-4 md:grid-cols-5">
        <Kpi label="Belegung" value={`${d.occupied}/${d.units.length}`} accent="text-brand-600" />
        <Kpi label="Check-ins heute" value={d.checkIns.length} accent="text-emerald-600" />
        <Kpi label="Check-outs heute" value={d.checkOuts.length} accent="text-amber-600" />
        <Kpi label="Offene Aufgaben" value={d.openTasks.length} />
        <Kpi label="Alloggiati offen" value={d.pendingAlloggiati} accent={d.pendingAlloggiati ? "text-red-600" : "text-emerald-600"} />
      </div>

      {/* Wohnungen */}
      <section>
        <h2 className="mb-3 text-lg font-semibold text-slate-700">Wohnungen</h2>
        <div className="grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-3">
          {d.units.map((u) => {
            const b = u.bookings[0];
            const guest = b?.guests[0]?.guest;
            return (
              <div key={u.id} className="rounded-xl border border-slate-200 bg-white p-4 shadow-sm">
                <div className="flex items-center justify-between">
                  <div className="flex items-center gap-2">
                    <span className="h-3 w-3 rounded-full" style={{ background: u.color }} />
                    <span className="font-semibold text-slate-800">{u.name}</span>
                  </div>
                  <span
                    className={`rounded-full px-2 py-0.5 text-xs font-medium ${
                      b ? "bg-red-100 text-red-700" : "bg-emerald-100 text-emerald-700"
                    }`}
                  >
                    {b ? "Belegt" : "Frei"}
                  </span>
                </div>
                <div className="mt-2 text-xs text-slate-500">
                  CIR: {u.cir ?? "—"} · CIN: {u.cin ?? "—"}
                </div>
                {b && guest && (
                  <div className="mt-3 rounded-lg bg-slate-50 p-2 text-sm">
                    <div className="font-medium text-slate-700">
                      {guest.firstName} {guest.lastName}
                    </div>
                    <div className="text-xs text-slate-500">
                      {fmt(b.checkIn)} → {fmt(b.checkOut)}
                    </div>
                  </div>
                )}
              </div>
            );
          })}
        </div>
      </section>

      {/* Check-ins / Check-outs */}
      <div className="grid grid-cols-1 gap-6 lg:grid-cols-2">
        <StayList title="Heutige Check-ins" rows={d.checkIns} fmt={fmt} accent="emerald" />
        <StayList title="Heutige Check-outs" rows={d.checkOuts} fmt={fmt} accent="amber" />
      </div>

      {/* Aufgaben */}
      <section>
        <h2 className="mb-3 text-lg font-semibold text-slate-700">Offene Aufgaben</h2>
        <div className="overflow-hidden rounded-xl border border-slate-200 bg-white">
          {d.openTasks.length === 0 && <p className="p-4 text-sm text-slate-500">Keine offenen Aufgaben 🎉</p>}
          {d.openTasks.map((t) => (
            <div key={t.id} className="flex items-center justify-between border-b border-slate-100 px-4 py-2 text-sm last:border-0">
              <span className="text-slate-700">{t.title}</span>
              <span className="text-xs text-slate-400">{t.dueDate ? fmt(t.dueDate) : "ohne Frist"}</span>
            </div>
          ))}
        </div>
      </section>
    </div>
  );
}

function StayList({
  title,
  rows,
  fmt,
  accent,
}: {
  title: string;
  rows: any[];
  fmt: (d: Date) => string;
  accent: "emerald" | "amber";
}) {
  return (
    <section>
      <h2 className="mb-3 text-lg font-semibold text-slate-700">{title}</h2>
      <div className="overflow-hidden rounded-xl border border-slate-200 bg-white">
        {rows.length === 0 && <p className="p-4 text-sm text-slate-500">Keine Einträge.</p>}
        {rows.map((b) => (
          <div key={b.id} className="flex items-center justify-between border-b border-slate-100 px-4 py-3 last:border-0">
            <div>
              <div className="font-medium text-slate-700">
                {b.guests[0] ? `${b.guests[0].guest.firstName} ${b.guests[0].guest.lastName}` : "—"}
              </div>
              <div className="text-xs text-slate-500">
                {b.unit.name} · {b.adults + b.children} Gäste
              </div>
            </div>
            <span className={`text-xs font-medium text-${accent}-600`}>
              {fmt(b.checkIn)} → {fmt(b.checkOut)}
            </span>
          </div>
        ))}
      </div>
    </section>
  );
}
