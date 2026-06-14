import { NextResponse } from "next/server";
import { prisma } from "@/lib/prisma";

/** GET /api/dashboard -> aggregierte Kennzahlen für das Haupt-Dashboard. */
export async function GET() {
  const today = new Date();
  const dayStart = new Date(Date.UTC(today.getFullYear(), today.getMonth(), today.getDate()));
  const dayEnd = new Date(dayStart);
  dayEnd.setUTCDate(dayEnd.getUTCDate() + 1);

  const [units, checkIns, checkOuts, inHouse, openTasks, pendingAlloggiati] = await Promise.all([
    prisma.unit.findMany({
      where: { active: true },
      orderBy: { name: "asc" },
      include: {
        bookings: {
          where: { status: { not: "CANCELLED" }, checkIn: { lt: dayEnd }, checkOut: { gt: dayStart } },
          include: { guests: { include: { guest: true } } },
        },
      },
    }),
    prisma.booking.findMany({
      where: { checkIn: { gte: dayStart, lt: dayEnd }, status: { not: "CANCELLED" } },
      include: { unit: true, guests: { include: { guest: true } } },
    }),
    prisma.booking.findMany({
      where: { checkOut: { gte: dayStart, lt: dayEnd }, status: { not: "CANCELLED" } },
      include: { unit: true, guests: { include: { guest: true } } },
    }),
    prisma.booking.count({
      where: { checkIn: { lt: dayEnd }, checkOut: { gt: dayStart }, status: { not: "CANCELLED" } },
    }),
    prisma.task.findMany({ where: { status: { not: "DONE" } }, orderBy: { dueDate: "asc" }, take: 20 }),
    // Anreisen heute, die noch nicht an Alloggiati Web gemeldet wurden
    prisma.booking.count({
      where: { checkIn: { gte: dayStart, lt: dayEnd }, alloggiatiSentAt: null, status: { not: "CANCELLED" } },
    }),
  ]);

  const occupiedUnits = units.filter((u) => u.bookings.length > 0).length;

  return NextResponse.json({
    date: dayStart.toISOString().slice(0, 10),
    kpis: {
      totalUnits: units.length,
      occupiedUnits,
      occupancyRate: units.length ? Math.round((occupiedUnits / units.length) * 100) : 0,
      inHouse,
      checkInsToday: checkIns.length,
      checkOutsToday: checkOuts.length,
      openTasks: openTasks.length,
      pendingAlloggiati,
    },
    units: units.map((u) => ({
      id: u.id,
      name: u.name,
      color: u.color,
      cir: u.cir,
      cin: u.cin,
      occupied: u.bookings.length > 0,
      currentBooking: u.bookings[0]
        ? {
            id: u.bookings[0].id,
            reference: u.bookings[0].reference,
            checkIn: u.bookings[0].checkIn,
            checkOut: u.bookings[0].checkOut,
            guestName: u.bookings[0].guests[0]
              ? `${u.bookings[0].guests[0].guest.firstName} ${u.bookings[0].guests[0].guest.lastName}`
              : "—",
          }
        : null,
    })),
    checkIns: checkIns.map(serializeStay),
    checkOuts: checkOuts.map(serializeStay),
    openTasks,
  });
}

function serializeStay(b: any) {
  return {
    id: b.id,
    reference: b.reference,
    unit: b.unit.name,
    checkIn: b.checkIn,
    checkOut: b.checkOut,
    guests: b.adults + b.children,
    guestName: b.guests[0] ? `${b.guests[0].guest.firstName} ${b.guests[0].guest.lastName}` : "—",
    alloggiatiSentAt: b.alloggiatiSentAt,
  };
}
