// Minimaler Auszug der "Codici Stato" für Alloggiati Web / ISTAT.
// In Produktion vollständig aus den offiziellen Tabellen der Polizia di Stato laden.
// Schlüssel: ISO-3166 alpha-2 -> Codice Stato (9-stellig).
export const STATE_CODES: Record<string, string> = {
  IT: "100000100", // ITALIA
  DE: "100000132", // GERMANIA
  FR: "100000109", // FRANCIA
  GB: "100000389", // REGNO UNITO
  US: "100000404", // STATI UNITI D'AMERICA
  ES: "100000134", // SPAGNA
  CH: "100000125", // SVIZZERA
  AT: "100000101", // AUSTRIA
  NL: "100000122", // PAESI BASSI
  BE: "100000103", // BELGIO
};

export function stateCode(isoAlpha2: string | null | undefined): string {
  if (!isoAlpha2) return STATE_CODES.IT;
  return STATE_CODES[isoAlpha2.toUpperCase()] ?? "100000100";
}
