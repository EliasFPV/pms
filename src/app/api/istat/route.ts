import { NextRequest, NextResponse } from "next/server";
import { prisma } from "@/lib/prisma";
import { buildIstatReport } from "@/lib/istat";

/**
 * GET /api/istat?from=YYYY-MM-DD&to=YYYY-MM-DD&unitId=
 * Aggregiert Ankünfte/Übernachtungen nach Herkunftsland für die ISTAT-Meldung.
 */
export async function GET(req: NextRequest) {
  const { searchParams } = new URL(req.url);
  const from = searchParams.get("from");
  const to = searchParams.get("to");
  const unitId = searchParams.get("unitId") ?? undefined;

  if (!from || !to) {
    return NextResponse.json({ error: "from und to erforderlich" }, { status: 400 });
  }

  const periodStart = new Date(from);
  const periodEnd = new Date(to);

  const bookings = await prisma.booking.findMany({
    where: {
      unitId,
      status: { not: "CANCELLED" },
      checkIn: { gte: periodStart, lte: periodEnd },
    },
    include: { guests: { include: { guest: { select: { country: true, countryOfBirth: true } } } } },
  });

  const report = buildIstatReport(
    bookings.map((b) => ({
      checkIn: b.checkIn,
      checkOut: b.checkOut,
      guests: b.guests.map((bg) => ({
        country: bg.guest.country,
        countryOfBirth: bg.guest.countryOfBirth,
      })),
    })),
    periodStart,
    periodEnd,
  );

  return NextResponse.json(report);
}
