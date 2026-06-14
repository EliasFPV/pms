import { prisma } from "./prisma";

/**
 * Prüft, ob eine Wohnung im Zeitraum [checkIn, checkOut) frei ist.
 * Berücksichtigt bestehende (nicht stornierte) Buchungen UND Blockierungen.
 * `excludeBookingId` erlaubt das Bearbeiten einer bestehenden Buchung.
 *
 * Überlappungslogik halboffener Intervalle: a.start < b.end && b.start < a.end.
 * Da checkOut exklusiv ist, ist eine Anreise am Abreisetag eines anderen erlaubt.
 */
export async function isAvailable(
  unitId: string,
  checkIn: Date,
  checkOut: Date,
  excludeBookingId?: string,
): Promise<boolean> {
  const [bookingConflict, blockConflict] = await Promise.all([
    prisma.booking.findFirst({
      where: {
        unitId,
        id: excludeBookingId ? { not: excludeBookingId } : undefined,
        status: { not: "CANCELLED" },
        checkIn: { lt: checkOut },
        checkOut: { gt: checkIn },
      },
      select: { id: true },
    }),
    prisma.block.findFirst({
      where: {
        unitId,
        startDate: { lt: checkOut },
        endDate: { gt: checkIn },
      },
      select: { id: true },
    }),
  ]);

  return !bookingConflict && !blockConflict;
}
