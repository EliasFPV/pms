import { NextRequest, NextResponse } from "next/server";
import { z } from "zod";
import { prisma } from "@/lib/prisma";
import { createBooking, BookingError } from "@/lib/bookings";

const createSchema = z.object({
  unitId: z.string().min(1),
  checkIn: z.string().regex(/^\d{4}-\d{2}-\d{2}$/),
  checkOut: z.string().regex(/^\d{4}-\d{2}-\d{2}$/),
  adults: z.number().int().min(1),
  children: z.number().int().min(0).default(0),
  childrenAges: z.array(z.number().int().min(0)).optional(),
  channel: z.enum(["DIRECT", "AIRBNB", "BOOKING_COM", "VRBO", "OTHER"]).optional(),
  depositAmount: z.number().min(0).optional(),
  notes: z.string().optional(),
  guests: z
    .array(z.object({ guestId: z.string(), role: z.string().optional(), isMinor: z.boolean().optional() }))
    .optional(),
});

// GET /api/bookings?from=&to=&unitId=  -> Buchungen (für Kalender/Liste)
export async function GET(req: NextRequest) {
  const { searchParams } = new URL(req.url);
  const from = searchParams.get("from");
  const to = searchParams.get("to");
  const unitId = searchParams.get("unitId") ?? undefined;

  const bookings = await prisma.booking.findMany({
    where: {
      unitId,
      status: { not: "CANCELLED" },
      ...(from && to
        ? { checkIn: { lt: new Date(to) }, checkOut: { gt: new Date(from) } }
        : {}),
    },
    include: { unit: { select: { id: true, name: true, color: true } }, guests: { include: { guest: true } } },
    orderBy: { checkIn: "asc" },
  });

  return NextResponse.json({ bookings });
}

// POST /api/bookings  -> neue Buchung erstellen (mit Doppelbuchungsschutz)
export async function POST(req: NextRequest) {
  try {
    const body = await req.json();
    const data = createSchema.parse(body);
    const booking = await createBooking(data);
    return NextResponse.json({ booking }, { status: 201 });
  } catch (err) {
    if (err instanceof z.ZodError) {
      return NextResponse.json({ error: "VALIDATION", issues: err.issues }, { status: 400 });
    }
    if (err instanceof BookingError) {
      const status = err.code === "NOT_AVAILABLE" ? 409 : 422;
      return NextResponse.json({ error: err.code, message: err.message }, { status });
    }
    console.error(err);
    return NextResponse.json({ error: "INTERNAL" }, { status: 500 });
  }
}
