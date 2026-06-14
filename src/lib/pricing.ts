import { Prisma } from "@prisma/client";
import { occupiedNights } from "./dates";

type RateLike = {
  startDate: Date;
  endDate: Date;
  pricePerNight: Prisma.Decimal | number;
  priority: number;
};

/**
 * Berechnet den Übernachtungspreis (ohne Reinigung/Kurtaxe).
 * Für jede Nacht wird die saisonale Rate mit höchster Priorität verwendet,
 * andernfalls der Basispreis der Wohnung.
 */
export function calcAccommodation(
  checkIn: Date,
  checkOut: Date,
  basePrice: number,
  seasonalRates: RateLike[],
): { total: number; perNight: { date: string; price: number }[] } {
  const nights = occupiedNights(checkIn, checkOut);
  const perNight = nights.map((night) => {
    const matching = seasonalRates
      .filter((r) => night >= new Date(r.startDate) && night <= new Date(r.endDate))
      .sort((a, b) => b.priority - a.priority);
    const price = matching.length ? Number(matching[0].pricePerNight) : basePrice;
    return { date: night.toISOString().slice(0, 10), price };
  });
  const total = perNight.reduce((s, n) => s + n.price, 0);
  return { total: round2(total), perNight };
}

export const round2 = (n: number) => Math.round((n + Number.EPSILON) * 100) / 100;
