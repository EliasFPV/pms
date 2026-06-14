import { NextResponse } from "next/server";
import { prisma } from "@/lib/prisma";

// GET /api/units -> alle aktiven Wohnungen (für Kalender-Zeilen, Buchungsformular)
export async function GET() {
  const units = await prisma.unit.findMany({
    where: { active: true },
    orderBy: { name: "asc" },
    include: { tassaConfig: true, seasonalRates: true },
  });
  return NextResponse.json({ units });
}
