import { differenceInCalendarDays, eachDayOfInterval, format, parseISO } from "date-fns";

/** Anzahl Nächte zwischen Check-in (inkl.) und Check-out (exkl.). */
export function nightsBetween(checkIn: Date | string, checkOut: Date | string): number {
  const a = typeof checkIn === "string" ? parseISO(checkIn) : checkIn;
  const b = typeof checkOut === "string" ? parseISO(checkOut) : checkOut;
  return Math.max(0, differenceInCalendarDays(b, a));
}

/** Liste aller belegten Nächte (Check-out-Tag ausgenommen). */
export function occupiedNights(checkIn: Date, checkOut: Date): Date[] {
  if (differenceInCalendarDays(checkOut, checkIn) <= 0) return [];
  // end - 1 Tag, da checkOut exklusiv ist
  const end = new Date(checkOut);
  end.setDate(end.getDate() - 1);
  return eachDayOfInterval({ start: checkIn, end });
}

/** Zwei halboffene Intervalle [aStart,aEnd) und [bStart,bEnd) überschneiden sich? */
export function intervalsOverlap(aStart: Date, aEnd: Date, bStart: Date, bEnd: Date): boolean {
  return aStart < bEnd && bStart < aEnd;
}

export const toISODate = (d: Date) => format(d, "yyyy-MM-dd");
export const toItDate = (d: Date) => format(d, "dd/MM/yyyy"); // Format für Alloggiati
