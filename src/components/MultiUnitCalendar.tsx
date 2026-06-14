"use client";

import { useEffect, useMemo, useState } from "react";
import {
  addMonths,
  eachDayOfInterval,
  endOfMonth,
  format,
  isSameDay,
  startOfMonth,
} from "date-fns";

interface Unit {
  id: string;
  name: string;
  color: string;
}
interface Booking {
  id: string;
  unitId: string;
  reference: string;
  checkIn: string;
  checkOut: string;
  adults: number;
  children: number;
  unit: { color: string };
  guests: { guest: { firstName: string; lastName: string } }[];
}

const CELL = 40; // px Breite pro Tag

export default function MultiUnitCalendar() {
  const [monthOffset, setMonthOffset] = useState(0);
  const [units, setUnits] = useState<Unit[]>([]);
  const [bookings, setBookings] = useState<Booking[]>([]);
  const [loading, setLoading] = useState(true);

  const monthStart = useMemo(() => startOfMonth(addMonths(new Date(), monthOffset)), [monthOffset]);
  const monthEnd = useMemo(() => endOfMonth(monthStart), [monthStart]);
  const days = useMemo(() => eachDayOfInterval({ start: monthStart, end: monthEnd }), [monthStart, monthEnd]);

  useEffect(() => {
    setLoading(true);
    const from = format(monthStart, "yyyy-MM-dd");
    const to = format(addMonths(monthStart, 1), "yyyy-MM-dd");
    Promise.all([
      fetch("/api/units").then((r) => r.json()),
      fetch(`/api/bookings?from=${from}&to=${to}`).then((r) => r.json()),
    ]).then(([u, b]) => {
      setUnits(u.units ?? []);
      setBookings(b.bookings ?? []);
      setLoading(false);
    });
  }, [monthStart]);

  // Position eines Buchungsbalkens innerhalb des sichtbaren Monats berechnen.
  function barStyle(b: Booking): React.CSSProperties | null {
    const ci = new Date(b.checkIn);
    const co = new Date(b.checkOut);
    const firstIdx = days.findIndex((d) => isSameDay(d, ci) || d > ci);
    if (firstIdx === -1) return null;
    const startIdx = ci < monthStart ? 0 : firstIdx;
    let endIdx = days.findIndex((d) => isSameDay(d, co));
    if (endIdx === -1) endIdx = co > monthEnd ? days.length : days.findIndex((d) => d > co);
    if (endIdx === -1) endIdx = days.length;
    const span = Math.max(1, endIdx - startIdx);
    // Balken startet am Mittag des Check-in-Tages, endet am Mittag des Check-out-Tages.
    return {
      left: startIdx * CELL + CELL / 2,
      width: span * CELL - CELL,
      background: b.unit.color,
    };
  }

  return (
    <div className="space-y-4">
      <div className="flex items-center justify-between">
        <h1 className="text-2xl font-bold text-slate-800">
          Belegungskalender · {format(monthStart, "MMMM yyyy")}
        </h1>
        <div className="flex gap-2">
          <button onClick={() => setMonthOffset((m) => m - 1)} className="rounded-md border border-slate-300 px-3 py-1.5 text-sm hover:bg-slate-100">
            ← Zurück
          </button>
          <button onClick={() => setMonthOffset(0)} className="rounded-md border border-slate-300 px-3 py-1.5 text-sm hover:bg-slate-100">
            Heute
          </button>
          <button onClick={() => setMonthOffset((m) => m + 1)} className="rounded-md border border-slate-300 px-3 py-1.5 text-sm hover:bg-slate-100">
            Weiter →
          </button>
        </div>
      </div>

      {loading ? (
        <div className="rounded-xl border border-slate-200 bg-white p-8 text-center text-slate-500">Lädt…</div>
      ) : (
        <div className="overflow-x-auto rounded-xl border border-slate-200 bg-white">
          <div style={{ width: 180 + days.length * CELL }}>
            {/* Kopfzeile mit Tagen */}
            <div className="flex border-b border-slate-200 bg-slate-50">
              <div className="sticky left-0 z-10 w-[180px] shrink-0 border-r border-slate-200 bg-slate-50 px-3 py-2 text-sm font-semibold text-slate-600">
                Wohnung
              </div>
              {days.map((day) => {
                const weekend = [0, 6].includes(day.getDay());
                const today = isSameDay(day, new Date());
                return (
                  <div
                    key={day.toISOString()}
                    style={{ width: CELL }}
                    className={`shrink-0 border-r border-slate-100 py-1 text-center text-xs ${
                      today ? "bg-brand-100 font-bold text-brand-700" : weekend ? "bg-slate-100 text-slate-500" : "text-slate-500"
                    }`}
                  >
                    <div>{format(day, "EEEEE")}</div>
                    <div className="font-medium text-slate-700">{format(day, "d")}</div>
                  </div>
                );
              })}
            </div>

            {/* Eine Zeile pro Wohnung */}
            {units.map((u) => {
              const unitBookings = bookings.filter((b) => b.unitId === u.id);
              return (
                <div key={u.id} className="relative flex border-b border-slate-100" style={{ height: 52 }}>
                  <div className="sticky left-0 z-10 flex w-[180px] shrink-0 items-center gap-2 border-r border-slate-200 bg-white px-3 text-sm font-medium text-slate-700">
                    <span className="h-3 w-3 rounded-full" style={{ background: u.color }} />
                    {u.name}
                  </div>
                  {/* Tagesraster */}
                  {days.map((day) => {
                    const weekend = [0, 6].includes(day.getDay());
                    return <div key={day.toISOString()} style={{ width: CELL }} className={`shrink-0 border-r border-slate-100 ${weekend ? "bg-slate-50" : ""}`} />;
                  })}
                  {/* Buchungsbalken */}
                  {unitBookings.map((b) => {
                    const style = barStyle(b);
                    if (!style) return null;
                    const g = b.guests[0]?.guest;
                    return (
                      <div
                        key={b.id}
                        title={`${b.reference} · ${b.checkIn.slice(0, 10)} → ${b.checkOut.slice(0, 10)}`}
                        className="absolute top-[10px] flex h-[32px] items-center overflow-hidden rounded-md px-2 text-xs font-medium text-white shadow"
                        style={style}
                      >
                        {g ? `${g.firstName} ${g.lastName}` : b.reference}
                      </div>
                    );
                  })}
                </div>
              );
            })}
          </div>
        </div>
      )}

      <p className="text-xs text-slate-500">
        Balken zeigen Belegungen (Anreise mittags → Abreise mittags). Doppelbuchungen werden serverseitig in
        <code className="mx-1 rounded bg-slate-100 px-1">/api/bookings</code> verhindert.
      </p>
    </div>
  );
}
