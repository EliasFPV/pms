import { NextRequest, NextResponse } from "next/server";
import { prisma } from "@/lib/prisma";
import { buildAlloggiatiFile, AlloggiatiGuest, AlloggiatiRole } from "@/lib/alloggiati";
import { stateCode } from "@/lib/codes";
import { nightsBetween } from "@/lib/dates";

/**
 * GET /api/alloggiati?date=YYYY-MM-DD  (Standard: heute)
 * Erzeugt die Alloggiati-Web-.txt-Datei für alle Anreisen (Check-ins) des Tages.
 * Die Meldung muss innerhalb von 24h nach Anreise erfolgen.
 * Optional ?markSent=1 setzt den Meldezeitpunkt der betroffenen Buchungen.
 */
export async function GET(req: NextRequest) {
  const { searchParams } = new URL(req.url);
  const dateStr = searchParams.get("date") ?? new Date().toISOString().slice(0, 10);
  const markSent = searchParams.get("markSent") === "1";

  const dayStart = new Date(`${dateStr}T00:00:00.000Z`);
  const dayEnd = new Date(`${dateStr}T23:59:59.999Z`);

  const bookings = await prisma.booking.findMany({
    where: { checkIn: { gte: dayStart, lte: dayEnd }, status: { not: "CANCELLED" } },
    include: { guests: { include: { guest: true } } },
  });

  const records: AlloggiatiGuest[] = [];
  for (const b of bookings) {
    const nights = nightsBetween(b.checkIn, b.checkOut);
    for (const bg of b.guests) {
      const g = bg.guest;
      records.push({
        role: bg.role as AlloggiatiRole,
        arrival: b.checkIn,
        nights,
        lastName: g.lastName,
        firstName: g.firstName,
        sex: g.sex,
        dateOfBirth: g.dateOfBirth,
        birthMunicipalityCode: null, // Codice Belfiore: in Produktion aus Comune-Tabelle auflösen
        birthProvince: g.province,
        birthStateCode: stateCode(g.countryOfBirth),
        citizenshipCode: stateCode(g.citizenship),
        documentType: g.documentType,
        documentNumber: g.documentNumber,
        documentIssuerCode: null, // Codice luogo rilascio: in Produktion auflösen
      });
    }
  }

  if (records.length === 0) {
    return NextResponse.json({ error: "Keine Anreisen für diesen Tag" }, { status: 404 });
  }

  const content = buildAlloggiatiFile(records);

  if (markSent) {
    await prisma.booking.updateMany({
      where: { id: { in: bookings.map((b) => b.id) } },
      data: { alloggiatiSentAt: new Date() },
    });
  }

  return new NextResponse(content, {
    status: 200,
    headers: {
      "Content-Type": "text/plain; charset=utf-8",
      "Content-Disposition": `attachment; filename="alloggiati_${dateStr}.txt"`,
    },
  });
}
