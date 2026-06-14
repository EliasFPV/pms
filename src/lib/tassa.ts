import { round2 } from "./pricing";

export interface TassaConfigInput {
  amountPerNight: number; // Betrag pro Person/Nacht
  maxChargedNights: number; // nur die ersten N Nächte zählen
  exemptUnderAge: number; // Kinder unter diesem Alter sind befreit
  enabled: boolean;
}

export interface TassaGuestInput {
  /** Alter des Gastes (für Kinder-Ausnahme). null = Erwachsener. */
  age: number | null;
}

export interface TassaResult {
  total: number;
  chargeableNights: number;
  payingGuests: number;
  exemptGuests: number;
  breakdown: string;
}

/**
 * Tassa di Soggiorno (Kurtaxe).
 * Formel: Betrag/Person/Nacht × zahlende Gäste × min(Nächte, maxChargedNights).
 * Kinder unter `exemptUnderAge` sind von der Taxe befreit.
 */
export function calcTassaSoggiorno(
  nights: number,
  guests: TassaGuestInput[],
  cfg: TassaConfigInput,
): TassaResult {
  if (!cfg.enabled || nights <= 0 || guests.length === 0) {
    return { total: 0, chargeableNights: 0, payingGuests: 0, exemptGuests: 0, breakdown: "Kurtaxe deaktiviert oder keine Gäste" };
  }

  const exemptGuests = guests.filter(
    (g) => g.age !== null && g.age < cfg.exemptUnderAge,
  ).length;
  const payingGuests = guests.length - exemptGuests;
  const chargeableNights = Math.min(nights, cfg.maxChargedNights);

  const total = round2(cfg.amountPerNight * payingGuests * chargeableNights);

  return {
    total,
    chargeableNights,
    payingGuests,
    exemptGuests,
    breakdown: `${cfg.amountPerNight.toFixed(2)} € × ${payingGuests} Gäste × ${chargeableNights} Nächte (max. ${cfg.maxChargedNights}; ${exemptGuests} Kinder < ${cfg.exemptUnderAge} J. befreit)`,
  };
}

/** Hilfsfunktion: Alter zu einem Stichtag aus Geburtsdatum berechnen. */
export function ageAt(dateOfBirth: Date | null | undefined, atDate: Date): number | null {
  if (!dateOfBirth) return null;
  const dob = new Date(dateOfBirth);
  let age = atDate.getFullYear() - dob.getFullYear();
  const m = atDate.getMonth() - dob.getMonth();
  if (m < 0 || (m === 0 && atDate.getDate() < dob.getDate())) age--;
  return age;
}
