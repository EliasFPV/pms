import { NextRequest, NextResponse } from "next/server";
import { z } from "zod";
import { prisma } from "@/lib/prisma";
import { calcTassaSoggiorno } from "@/lib/tassa";
import { nightsBetween } from "@/lib/dates";

const schema = z.object({
  unitId: z.string(),
  checkIn: z.string(),
  checkOut: z.string(),
  adults: z.number().int().min(0),
  childrenAges: z.array(z.number().int().min(0)).default([]),
});

// POST /api/tassa -> Kurtaxe für einen Aufenthalt berechnen (Vorschau)
export async function POST(req: NextRequest) {
  const body = await req.json();
  const data = schema.parse(body);

  const cfg = await prisma.tassaConfig.findUnique({ where: { unitId: data.unitId } });
  if (!cfg) return NextResponse.json({ error: "Keine Kurtaxen-Konfiguration für die Wohnung" }, { status: 404 });

  const nights = nightsBetween(data.checkIn, data.checkOut);
  const guests = [
    ...Array(data.adults).fill({ age: null }),
    ...data.childrenAges.map((age) => ({ age })),
  ];

  const result = calcTassaSoggiorno(nights, guests, {
    amountPerNight: Number(cfg.amountPerNight),
    maxChargedNights: cfg.maxChargedNights,
    exemptUnderAge: cfg.exemptUnderAge,
    enabled: cfg.enabled,
  });

  return NextResponse.json({ nights, ...result });
}
