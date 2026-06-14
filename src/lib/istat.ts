// =============================================================================
//  ISTAT – Aggregation der Daten für die regionale Tourismusstatistik
//  (Gästeherkunft, Anzahl Ankünfte/"arrivi" und Übernachtungen/"presenze",
//   durchschnittliche Aufenthaltsdauer)
// =============================================================================

import { nightsBetween } from "./dates";

export interface IstatBookingInput {
  checkIn: Date;
  checkOut: Date;
  guests: { countryOfBirth: string | null; country: string | null }[];
}

export interface IstatCountryRow {
  country: string; // ISO alpha-2
  arrivals: number; // arrivi (Anzahl Gäste, die anreisen)
  nights: number; // presenze (Gäste × Nächte)
}

export interface IstatReport {
  periodStart: string;
  periodEnd: string;
  totalArrivals: number;
  totalNights: number;
  averageStay: number;
  byCountry: IstatCountryRow[];
}

/** Aggregiert Buchungen eines Zeitraums nach Herkunftsland. */
export function buildIstatReport(
  bookings: IstatBookingInput[],
  periodStart: Date,
  periodEnd: Date,
): IstatReport {
  const map = new Map<string, IstatCountryRow>();
  let totalArrivals = 0;
  let totalNights = 0;

  for (const b of bookings) {
    const nights = nightsBetween(b.checkIn, b.checkOut);
    for (const g of b.guests) {
      const country = (g.country || g.countryOfBirth || "XX").toUpperCase();
      const row = map.get(country) ?? { country, arrivals: 0, nights: 0 };
      row.arrivals += 1;
      row.nights += nights;
      map.set(country, row);
      totalArrivals += 1;
      totalNights += nights;
    }
  }

  const byCountry = [...map.values()].sort((a, b) => b.nights - a.nights);

  return {
    periodStart: periodStart.toISOString().slice(0, 10),
    periodEnd: periodEnd.toISOString().slice(0, 10),
    totalArrivals,
    totalNights,
    averageStay: totalArrivals ? Math.round((totalNights / totalArrivals) * 100) / 100 : 0,
    byCountry,
  };
}
