import { PrismaClient, Prisma } from "@prisma/client";

const prisma = new PrismaClient();

const UNITS = [
  { name: "Casa Vista Mare", ref: "U1", color: "#2f6fed", base: 120, cir: "IT065006C2..001", cin: "IT065006B5XXXX01" },
  { name: "Appartamento Limoneto", ref: "U2", color: "#16a34a", base: 95, cir: "IT065006C2..002", cin: "IT065006B5XXXX02" },
  { name: "Loft Centro Storico", ref: "U3", color: "#f59e0b", base: 110, cir: "IT065006C2..003", cin: "IT065006B5XXXX03" },
  { name: "Villetta Uliveto", ref: "U4", color: "#db2777", base: 150, cir: "IT065006C2..004", cin: "IT065006B5XXXX04" },
  { name: "Monolocale Belvedere", ref: "U5", color: "#0891b2", base: 70, cir: "IT065006C2..005", cin: "IT065006B5XXXX05" },
];

function d(s: string) {
  return new Date(`${s}T00:00:00.000Z`);
}

async function main() {
  console.log("Seeding…");
  await prisma.payment.deleteMany();
  await prisma.bookingGuest.deleteMany();
  await prisma.invoice.deleteMany();
  await prisma.booking.deleteMany();
  await prisma.block.deleteMany();
  await prisma.seasonalRate.deleteMany();
  await prisma.tassaConfig.deleteMany();
  await prisma.task.deleteMany();
  await prisma.guest.deleteMany();
  await prisma.unit.deleteMany();

  const units = [];
  for (const u of UNITS) {
    const unit = await prisma.unit.create({
      data: {
        name: u.name,
        internalRef: u.ref,
        address: "Via del Mare 10",
        city: "Amalfi",
        province: "SA",
        region: "Campania",
        postalCode: "84011",
        maxGuests: 4,
        color: u.color,
        cir: u.cir,
        cin: u.cin,
        basePrice: new Prisma.Decimal(u.base),
        cleaningFee: new Prisma.Decimal(50),
        tassaConfig: {
          create: { amountPerNight: new Prisma.Decimal(2), maxChargedNights: 7, exemptUnderAge: 12, enabled: true },
        },
        seasonalRates: {
          create: [
            { name: "Hochsaison Sommer", startDate: d("2026-07-01"), endDate: d("2026-08-31"), pricePerNight: new Prisma.Decimal(u.base * 1.6), minNights: 5, priority: 10 },
            { name: "Weihnachten", startDate: d("2026-12-22"), endDate: d("2027-01-06"), pricePerNight: new Prisma.Decimal(u.base * 1.4), minNights: 3, priority: 10 },
          ],
        },
      },
    });
    units.push(unit);
  }

  // Gäste
  const mario = await prisma.guest.create({
    data: {
      firstName: "Mario", lastName: "Rossi", email: "mario.rossi@example.it", phone: "+39 333 1234567",
      sex: "M", dateOfBirth: d("1980-05-12"), country: "IT", countryOfBirth: "IT", citizenship: "IT",
      province: "RM", city: "Roma", documentType: "IDENT_CARD", documentNumber: "AB1234567",
    },
  });
  const anna = await prisma.guest.create({
    data: {
      firstName: "Anna", lastName: "Müller", email: "anna.mueller@example.de", phone: "+49 170 1234567",
      sex: "F", dateOfBirth: d("1990-09-03"), country: "DE", countryOfBirth: "DE", citizenship: "DE",
      documentType: "PASSPORT", documentNumber: "C01X00T47",
    },
  });
  const kind = await prisma.guest.create({
    data: { firstName: "Lena", lastName: "Müller", sex: "F", dateOfBirth: d("2018-04-01"), country: "DE", countryOfBirth: "DE", citizenship: "DE" },
  });

  const today = new Date();
  const iso = (offset: number) => {
    const x = new Date(today);
    x.setDate(x.getDate() + offset);
    return x.toISOString().slice(0, 10);
  };

  // Buchung 1: läuft heute (in-house)
  await prisma.booking.create({
    data: {
      reference: "BK-2026-DEMO1", unitId: units[0].id, checkIn: d(iso(-2)), checkOut: d(iso(3)),
      adults: 2, children: 0, nights: 5,
      accommodation: new Prisma.Decimal(600), cleaningFee: new Prisma.Decimal(50),
      tassaSoggiorno: new Prisma.Decimal(20), totalAmount: new Prisma.Decimal(670), depositAmount: new Prisma.Decimal(201),
      guests: { create: [{ guestId: mario.id, role: "SINGLE" }] },
    },
  });

  // Buchung 2: Check-in heute (Familie aus DE)
  await prisma.booking.create({
    data: {
      reference: "BK-2026-DEMO2", unitId: units[1].id, checkIn: d(iso(0)), checkOut: d(iso(7)),
      adults: 2, children: 1, nights: 7,
      accommodation: new Prisma.Decimal(665), cleaningFee: new Prisma.Decimal(50),
      tassaSoggiorno: new Prisma.Decimal(28), totalAmount: new Prisma.Decimal(743), depositAmount: new Prisma.Decimal(223),
      guests: { create: [{ guestId: anna.id, role: "FAMILY_HEAD" }, { guestId: kind.id, role: "FAMILY_MEMBER", isMinor: true }] },
    },
  });

  // Blockierung: Wartung U3
  await prisma.block.create({
    data: { unitId: units[2].id, startDate: d(iso(1)), endDate: d(iso(4)), reason: "MAINTENANCE", note: "Boiler-Austausch" },
  });

  // Aufgaben
  await prisma.task.createMany({
    data: [
      { title: "Reinigung Casa Vista Mare nach Check-out", unitId: units[0].id, dueDate: d(iso(3)), type: "CLEANING" },
      { title: "Willkommenskorb Limoneto vorbereiten", unitId: units[1].id, dueDate: d(iso(0)), type: "CHECKIN_PREP" },
      { title: "Alloggiati-Meldung prüfen", type: "ADMIN", dueDate: d(iso(0)) },
    ],
  });

  console.log(`Seed fertig: ${units.length} Wohnungen, 3 Gäste, 2 Buchungen.`);
}

main()
  .catch((e) => {
    console.error(e);
    process.exit(1);
  })
  .finally(() => prisma.$disconnect());
