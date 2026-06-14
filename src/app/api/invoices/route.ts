import { NextRequest, NextResponse } from "next/server";
import { z } from "zod";
import { Prisma } from "@prisma/client";
import { prisma } from "@/lib/prisma";
import { buildFatturaPaXml } from "@/lib/fatturapa";
import { round2 } from "@/lib/pricing";
import { format } from "date-fns";

const schema = z.object({
  bookingId: z.string(),
  recipientName: z.string(),
  recipientFiscalCode: z.string().optional(),
  recipientVat: z.string().optional(),
  recipientSdiCode: z.string().optional(),
  recipientPec: z.string().optional(),
  vatRate: z.number().min(0).max(100).default(0),
});

// Anbieterdaten würden i.d.R. aus einer Settings-Tabelle kommen.
const SUPPLIER = {
  vat: "01234567890",
  name: "Gestione Case Vacanza S.r.l.",
  address: "Via Roma 1",
  zip: "84011",
  city: "Amalfi",
  province: "SA",
  taxRegime: "RF19", // Regime forfettario
};

// POST /api/invoices -> Rechnung + FatturaPA-XML aus einer Buchung erzeugen
export async function POST(req: NextRequest) {
  const data = schema.parse(await req.json());

  const booking = await prisma.booking.findUnique({
    where: { id: data.bookingId },
    include: { unit: true },
  });
  if (!booking) return NextResponse.json({ error: "Buchung nicht gefunden" }, { status: 404 });

  // imponibile = Übernachtung + Reinigung (Kurtaxe ist durchlaufender Posten, hier separat)
  const taxable = round2(Number(booking.accommodation) + Number(booking.cleaningFee));
  const vatAmount = round2((taxable * data.vatRate) / 100);
  const total = round2(taxable + vatAmount);

  // Progressivo: Jahr/laufende Nummer
  const year = new Date().getFullYear();
  const count = await prisma.invoice.count({ where: { number: { startsWith: `${year}/` } } });
  const number = `${year}/${String(count + 1).padStart(4, "0")}`;

  const xml = buildFatturaPaXml({
    supplier: SUPPLIER,
    number,
    issueDate: format(new Date(), "yyyy-MM-dd"),
    recipientName: data.recipientName,
    recipientVat: data.recipientVat,
    recipientFiscalCode: data.recipientFiscalCode,
    recipientSdiCode: data.recipientSdiCode,
    recipientPec: data.recipientPec,
    taxableAmount: taxable,
    vatRate: data.vatRate,
    vatAmount,
    totalAmount: total,
    cir: booking.unit.cir ?? undefined,
    cin: booking.unit.cin ?? undefined,
    description: `Locazione breve – ${booking.unit.name}, ${format(booking.checkIn, "dd.MM")}–${format(booking.checkOut, "dd.MM.yyyy")}`,
  });

  const invoice = await prisma.invoice.upsert({
    where: { bookingId: booking.id },
    create: {
      bookingId: booking.id,
      number,
      issueDate: new Date(),
      status: "ISSUED",
      recipientName: data.recipientName,
      recipientVat: data.recipientVat,
      recipientFiscalCode: data.recipientFiscalCode,
      recipientSdiCode: data.recipientSdiCode ?? "0000000",
      recipientPec: data.recipientPec,
      taxableAmount: new Prisma.Decimal(taxable),
      vatRate: new Prisma.Decimal(data.vatRate),
      vatAmount: new Prisma.Decimal(vatAmount),
      totalAmount: new Prisma.Decimal(total),
      cir: booking.unit.cir,
      cin: booking.unit.cin,
      xmlPayload: xml,
    },
    update: { xmlPayload: xml },
  });

  return NextResponse.json({ invoice: { id: invoice.id, number: invoice.number }, xml }, { status: 201 });
}
