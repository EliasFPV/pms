// =============================================================================
//  Fatturazione Elettronica – Generierung des FatturaPA-XML für das
//  italienische Sistema di Interscambio (SdI).
//  Minimaler, valider Aufbau (FatturaElettronica v1.2.2, TD01).
//  CIR/CIN der Wohnung werden als Pflichtangaben im Beschreibungsfeld geführt.
// =============================================================================

export interface SupplierData {
  vat: string; // Partita IVA (CedentePrestatore)
  fiscalCode?: string;
  name: string;
  address: string;
  zip: string;
  city: string;
  province: string;
  taxRegime: string; // z.B. "RF19" (forfettario) oder "RF01"
}

export interface FatturaInput {
  supplier: SupplierData;
  number: string; // progressivo, z.B. "2026/0001"
  issueDate: string; // YYYY-MM-DD
  recipientName: string;
  recipientVat?: string;
  recipientFiscalCode?: string;
  recipientSdiCode?: string; // Codice Destinatario (Default 0000000)
  recipientPec?: string;
  recipientCountry?: string;
  recipientAddress?: string;
  recipientCity?: string;
  recipientZip?: string;
  recipientProvince?: string;
  taxableAmount: number;
  vatRate: number; // %
  vatAmount: number;
  totalAmount: number;
  cir?: string;
  cin?: string;
  description: string; // z.B. "Locazione breve – Casa Vista Mare, 20.06–27.06.2026"
}

const esc = (s: string) =>
  s.replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;").replace(/"/g, "&quot;");
const dec = (n: number) => n.toFixed(2);

/** Erzeugt den ProgressivoInvio (eindeutige Übertragungs-ID, alphanumerisch). */
export function progressivoInvio(seed: string): string {
  return seed.replace(/[^A-Za-z0-9]/g, "").slice(-5).toUpperCase().padStart(5, "0");
}

export function buildFatturaPaXml(input: FatturaInput): string {
  const s = input.supplier;
  const natura = input.vatRate === 0 ? `<Natura>N2.2</Natura>` : ""; // nicht steuerbar/befreit
  const descLines = [input.description];
  if (input.cir) descLines.push(`CIR: ${input.cir}`);
  if (input.cin) descLines.push(`CIN: ${input.cin}`);
  const fullDesc = esc(descLines.join(" - "));

  const formatoTrasmissione = "FPR12"; // privati
  const codiceDestinatario = (input.recipientSdiCode || "0000000").padStart(7, "0");

  return `<?xml version="1.0" encoding="UTF-8"?>
<p:FatturaElettronica versione="${formatoTrasmissione}" xmlns:p="http://ivaservizi.agenziaentrate.gov.it/docs/xsd/fatture/v1.2">
  <FatturaElettronicaHeader>
    <DatiTrasmissione>
      <IdTrasmittente><IdPaese>IT</IdPaese><IdCodice>${esc(s.vat)}</IdCodice></IdTrasmittente>
      <ProgressivoInvio>${progressivoInvio(input.number)}</ProgressivoInvio>
      <FormatoTrasmissione>${formatoTrasmissione}</FormatoTrasmissione>
      <CodiceDestinatario>${codiceDestinatario}</CodiceDestinatario>
      ${input.recipientPec ? `<PECDestinatario>${esc(input.recipientPec)}</PECDestinatario>` : ""}
    </DatiTrasmissione>
    <CedentePrestatore>
      <DatiAnagrafici>
        <IdFiscaleIVA><IdPaese>IT</IdPaese><IdCodice>${esc(s.vat)}</IdCodice></IdFiscaleIVA>
        <Anagrafica><Denominazione>${esc(s.name)}</Denominazione></Anagrafica>
        <RegimeFiscale>${esc(s.taxRegime)}</RegimeFiscale>
      </DatiAnagrafici>
      <Sede>
        <Indirizzo>${esc(s.address)}</Indirizzo><CAP>${esc(s.zip)}</CAP>
        <Comune>${esc(s.city)}</Comune><Provincia>${esc(s.province)}</Provincia><Nazione>IT</Nazione>
      </Sede>
    </CedentePrestatore>
    <CessionarioCommittente>
      <DatiAnagrafici>
        ${input.recipientVat ? `<IdFiscaleIVA><IdPaese>${esc(input.recipientCountry || "IT")}</IdPaese><IdCodice>${esc(input.recipientVat)}</IdCodice></IdFiscaleIVA>` : ""}
        ${input.recipientFiscalCode ? `<CodiceFiscale>${esc(input.recipientFiscalCode)}</CodiceFiscale>` : ""}
        <Anagrafica><Denominazione>${esc(input.recipientName)}</Denominazione></Anagrafica>
      </DatiAnagrafici>
      <Sede>
        <Indirizzo>${esc(input.recipientAddress || "N/A")}</Indirizzo><CAP>${esc(input.recipientZip || "00000")}</CAP>
        <Comune>${esc(input.recipientCity || "N/A")}</Comune><Nazione>${esc(input.recipientCountry || "IT")}</Nazione>
      </Sede>
    </CessionarioCommittente>
  </FatturaElettronicaHeader>
  <FatturaElettronicaBody>
    <DatiGenerali>
      <DatiGeneraliDocumento>
        <TipoDocumento>TD01</TipoDocumento><Divisa>EUR</Divisa>
        <Data>${input.issueDate}</Data><Numero>${esc(input.number)}</Numero>
        <ImportoTotaleDocumento>${dec(input.totalAmount)}</ImportoTotaleDocumento>
      </DatiGeneraliDocumento>
    </DatiGenerali>
    <DatiBeniServizi>
      <DettaglioLinee>
        <NumeroLinea>1</NumeroLinea>
        <Descrizione>${fullDesc}</Descrizione>
        <Quantita>1.00</Quantita>
        <PrezzoUnitario>${dec(input.taxableAmount)}</PrezzoUnitario>
        <PrezzoTotale>${dec(input.taxableAmount)}</PrezzoTotale>
        <AliquotaIVA>${dec(input.vatRate)}</AliquotaIVA>
        ${natura}
      </DettaglioLinee>
      <DatiRiepilogo>
        <AliquotaIVA>${dec(input.vatRate)}</AliquotaIVA>
        ${natura}
        <ImponibileImporto>${dec(input.taxableAmount)}</ImponibileImporto>
        <Imposta>${dec(input.vatAmount)}</Imposta>
      </DatiRiepilogo>
    </DatiBeniServizi>
  </FatturaElettronicaBody>
</p:FatturaElettronica>`;
}
