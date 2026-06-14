import { Prisma } from "@prisma/client";
import { prisma } from "./prisma";
import { isAvailable } from "./availability";
import { calcAccommodation, round2 } from "./pricing";
import { calcTassaSoggiorno } from "./tassa";
import { nightsBetween } from "./dates";

export interface CreateBookingInput {
  unitId: string;
  checkIn: string; // ISO date
  checkOut: string; // ISO date
  adults: number;
  children: number; // Anzahl Kinder
  childrenAges?: number[]; // Alter der Kinder (für Kurtaxen-Ausnahme)
  channel?: "DIRECT" | "AIRBNB" | "BOOKING_COM" | "VRBO" | "OTHER";
  depositAmount?: number;
  notes?: string;
  guests?: { guestId: string; role?: string; isMinor?: boolean }[];
}

export class BookingError extends Error {
  constructor(message: string, public code = "BOOKING_ERROR") {
    super(message);
  }
}

/** Erstellt eine Buchung transaktional inkl. Verfügbarkeitsprüfung & Preisberechnung. */
export async function createBooking(input: CreateBookingInput) {
  const checkIn = new Date(input.checkIn);
  const checkOut = new Date(input.checkOut);
  const nights = nightsBetween(checkIn, checkOut);

  if (nights <= 0) throw new BookingError("Check-out muss nach Check-in liegen.", "INVALID_DATES");

  const unit = await prisma.unit.findUnique({
    where: { id: input.unitId },
    include: { seasonalRates: true, tassaConfig: true },
  });
  if (!unit) throw new BookingError("Wohnung nicht gefunden.", "UNIT_NOT_FOUND");

  const totalGuests = input.adults + input.children;
  if (totalGuests > unit.maxGuests) {
    throw new BookingError(`Maximale Belegung (${unit.maxGuests}) überschritten.`, "OVER_CAPACITY");
  }

  // 1) Doppelbuchung verhindern
  if (!(await isAvailable(unit.id, checkIn, checkOut))) {
    throw new BookingError("Zeitraum ist nicht verfügbar (Doppelbuchung/Blockierung).", "NOT_AVAILABLE");
  }

  // 2) Übernachtungspreis (Basispreis + saisonale Raten)
  const accommodation = calcAccommodation(checkIn, checkOut, Number(unit.basePrice), unit.seasonalRates);

  // 3) Kurtaxe
  const tassaGuests = [
    ...Array(input.adults).fill({ age: null }),
    ...(input.childrenAges ?? Array(input.children).fill(0)).map((age) => ({ age })),
  ];
  const tassa = unit.tassaConfig
    ? calcTassaSoggiorno(nights, tassaGuests, {
        amountPerNight: Number(unit.tassaConfig.amountPerNight),
        maxChargedNights: unit.tassaConfig.maxChargedNights,
        exemptUnderAge: unit.tassaConfig.exemptUnderAge,
        enabled: unit.tassaConfig.enabled,
      })
    : { total: 0, breakdown: "Keine Kurtaxen-Konfiguration" };

  const cleaningFee = Number(unit.cleaningFee);
  const totalAmount = round2(accommodation.total + cleaningFee + tassa.total);

  // 4) Transaktional speichern (erneute Verfügbarkeitsprüfung als Race-Schutz)
  const reference = `BK-${new Date().getFullYear()}-${Math.random().toString(36).slice(2, 7).toUpperCase()}`;

  return prisma.$transaction(async (tx) => {
    const conflict = await tx.booking.findFirst({
      where: {
        unitId: unit.id,
        status: { not: "CANCELLED" },
        checkIn: { lt: checkOut },
        checkOut: { gt: checkIn },
      },
      select: { id: true },
    });
    if (conflict) throw new BookingError("Zeitraum wurde gerade belegt.", "NOT_AVAILABLE");

    return tx.booking.create({
      data: {
        reference,
        unitId: unit.id,
        channel: input.channel ?? "DIRECT",
        checkIn,
        checkOut,
        adults: input.adults,
        children: input.children,
        nights,
        accommodation: new Prisma.Decimal(accommodation.total),
        cleaningFee: new Prisma.Decimal(cleaningFee),
        tassaSoggiorno: new Prisma.Decimal(tassa.total),
        totalAmount: new Prisma.Decimal(totalAmount),
        depositAmount: new Prisma.Decimal(input.depositAmount ?? round2(totalAmount * 0.3)),
        notes: input.notes,
        guests: input.guests?.length
          ? {
              create: input.guests.map((g) => ({
                guestId: g.guestId,
                role: (g.role as any) ?? "SINGLE",
                isMinor: g.isMinor ?? false,
              })),
            }
          : undefined,
      },
      include: { unit: true, guests: { include: { guest: true } } },
    });
  });
}
