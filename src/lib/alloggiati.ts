// =============================================================================
//  Alloggiati Web – Generierung der .txt-Exportdatei (Polizia di Stato)
//
//  Jeder Datensatz ist EIN Gast = eine fixe Zeile von 168 Zeichen.
//  Felder werden rechts mit Leerzeichen aufgefüllt (Strings) bzw. wie
//  spezifiziert formatiert. Zeilen werden mit CRLF (\r\n) getrennt.
//
//  Feldaufbau (Offset / Länge):
//   1  Tipo Alloggiato        2   (16/17/18/19/20)
//   2  Data Arrivo           10   gg/mm/aaaa
//   3  Giorni di Permanenza   2   01..30
//   4  Cognome               50
//   5  Nome                  30
//   6  Sesso                  1   1=M, 2=F
//   7  Data Nascita          10   gg/mm/aaaa
//   8  Comune Nascita         9   (Codice Belfiore – nur wenn in IT geboren)
//   9  Provincia Nascita      2   (nur wenn in IT geboren, sonst leer)
//   10 Stato Nascita          9   (Codice Stato)
//   11 Cittadinanza           9   (Codice Stato)
//   12 Tipo Documento         5   (nur Meldepflichtige: 16/17/18)
//   13 Numero Documento      20   (nur Meldepflichtige)
//   14 Luogo Rilascio Doc.    9   (Codice – nur Meldepflichtige)
//  Gesamt = 168
// =============================================================================

import { toItDate } from "./dates";

export type AlloggiatiRole =
  | "SINGLE"
  | "FAMILY_HEAD"
  | "GROUP_HEAD"
  | "FAMILY_MEMBER"
  | "GROUP_MEMBER";

const TIPO_ALLOGGIATO: Record<AlloggiatiRole, string> = {
  SINGLE: "16",
  FAMILY_HEAD: "17",
  GROUP_HEAD: "18",
  FAMILY_MEMBER: "19",
  GROUP_MEMBER: "20",
};

// Meldepflichtige Rollen müssen ein Ausweisdokument angeben.
const REQUIRES_DOCUMENT: Set<AlloggiatiRole> = new Set(["SINGLE", "FAMILY_HEAD", "GROUP_HEAD"]);

const DOC_CODES: Record<string, string> = {
  IDENT_CARD: "IDENT",
  PASSPORT: "PASOR",
  DRIVING_LICENSE: "PATEN",
};

export interface AlloggiatiGuest {
  role: AlloggiatiRole;
  arrival: Date;
  nights: number; // Giorni di permanenza
  lastName: string;
  firstName: string;
  sex: "M" | "F" | null;
  dateOfBirth: Date | null;
  birthMunicipalityCode: string | null; // Codice Belfiore (IT)
  birthProvince: string | null; // Provinzkürzel (IT)
  birthStateCode: string; // Codice Stato (z.B. 100000100 = ITALIA)
  citizenshipCode: string; // Codice Stato
  documentType: string | null; // IDENT_CARD | PASSPORT | DRIVING_LICENSE
  documentNumber: string | null;
  documentIssuerCode: string | null; // Codice luogo rilascio
}

/** Padding rechts (Strings); schneidet bei Überlänge ab. Diakritika werden entfernt. */
function fixed(value: string | null | undefined, len: number): string {
  const clean = normalize(value ?? "");
  return clean.length >= len ? clean.slice(0, len) : clean.padEnd(len, " ");
}

/** Großbuchstaben, Akzente entfernt – Alloggiati erlaubt nur A-Z, 0-9, Leerzeichen. */
function normalize(s: string): string {
  return s
    .normalize("NFD")
    .replace(/[̀-ͯ]/g, "")
    .toUpperCase()
    .replace(/[^A-Z0-9 /]/g, " ");
}

const ITALY_STATE_CODE = "100000100";

/** Erzeugt eine einzelne 168-Zeichen-Zeile für einen Gast. */
export function buildAlloggiatiRecord(g: AlloggiatiGuest): string {
  const bornInItaly = g.birthStateCode === ITALY_STATE_CODE;
  const requiresDoc = REQUIRES_DOCUMENT.has(g.role);

  let line = "";
  line += fixed(TIPO_ALLOGGIATO[g.role], 2);
  line += fixed(toItDate(g.arrival), 10);
  line += String(Math.min(Math.max(g.nights, 1), 30)).padStart(2, "0"); // 2
  line += fixed(g.lastName, 50);
  line += fixed(g.firstName, 30);
  line += fixed(g.sex === "M" ? "1" : g.sex === "F" ? "2" : "", 1);
  line += fixed(g.dateOfBirth ? toItDate(g.dateOfBirth) : "", 10);
  line += fixed(bornInItaly ? g.birthMunicipalityCode : "", 9);
  line += fixed(bornInItaly ? g.birthProvince : "", 2);
  line += fixed(g.birthStateCode, 9);
  line += fixed(g.citizenshipCode, 9);
  line += fixed(requiresDoc && g.documentType ? DOC_CODES[g.documentType] ?? "" : "", 5);
  line += fixed(requiresDoc ? g.documentNumber : "", 20);
  line += fixed(requiresDoc ? g.documentIssuerCode : "", 9);

  // Sicherheitsnetz: exakt 168 Zeichen erzwingen.
  return line.padEnd(168, " ").slice(0, 168);
}

/** Erzeugt die gesamte .txt-Datei (CRLF-getrennt, abschließendes CRLF). */
export function buildAlloggiatiFile(guests: AlloggiatiGuest[]): string {
  return guests.map(buildAlloggiatiRecord).join("\r\n") + "\r\n";
}

export { TIPO_ALLOGGIATO, REQUIRES_DOCUMENT, ITALY_STATE_CODE };
