# PMS Italia 🇮🇹

Maßgeschneidertes **Property Management System** für die Verwaltung von 5 Ferienwohnungen in Italien – inklusive der zwingenden italienischen Compliance-Anforderungen.

## Tech-Stack

| Schicht      | Technologie                              | Begründung |
|--------------|------------------------------------------|------------|
| Frontend     | **Next.js 15 (App Router) + React 19 + TypeScript** | Eine Codebasis für UI und API (Server Actions/Route Handlers), SSR für schnelle Dashboards |
| Styling      | **Tailwind CSS**                         | Schnelles, konsistentes UI ohne CSS-Overhead |
| Backend      | **Next.js Route Handlers (Node)**        | Kein separater Server nötig, typsicher mit Zod |
| ORM          | **Prisma 6**                             | Typsichere DB-Zugriffe, Migrationen, Transaktionen |
| Datenbank    | **PostgreSQL 16**                        | Relationale Integrität (Wohnungen↔Buchungen↔Gäste↔Zahlungen), `Decimal` für Geld |
| Validierung  | **Zod**                                  | Laufzeit-Validierung der API-Eingaben |

## Funktionsumfang

- **Dashboard** (`/`) – Belegung der 5 Wohnungen, Check-ins/-outs heute, offene Aufgaben, offene Alloggiati-Meldungen.
- **Multi-Unit-Belegungskalender** (`/calendar`) – visuelle Monatsansicht, eine Zeile pro Wohnung.
- **Gäste-CRM** – `Guest`-Modell inkl. Ausweisdaten.
- **Financials & Pricing** – Basispreis, saisonale Raten, Reinigungsgebühr, Anzahlung/Restzahlung.

### Italien-Compliance

| Anforderung | Implementierung |
|-------------|-----------------|
| **Tassa di Soggiorno** | `src/lib/tassa.ts` + `TassaConfig` (Betrag/Person/Nacht, max. Nächte, Kinder-Ausnahme). API: `POST /api/tassa` |
| **Alloggiati Web** | `src/lib/alloggiati.ts` – exaktes 168-Zeichen-Fixformat. API: `GET /api/alloggiati?date=YYYY-MM-DD` → `.txt` |
| **ISTAT** | `src/lib/istat.ts` – Aggregation Herkunft/Übernachtungen. API: `GET /api/istat?from=&to=` |
| **CIR / CIN** | Felder pro `Unit`, erscheinen auf Dashboard & Rechnung |
| **Fatturazione Elettronica** | `src/lib/fatturapa.ts` – FatturaPA-XML (TD01) für SdI. API: `POST /api/invoices` |

## Setup

```bash
# 1) Postgres starten
docker compose up -d

# 2) Abhängigkeiten & Env
cp .env.example .env
npm install

# 3) Schema in DB anlegen + Demodaten
npm run db:push
npm run db:seed

# 4) App starten
npm run dev   # http://localhost:3000
```

## API-Überblick

| Methode | Route | Zweck |
|---------|-------|-------|
| GET  | `/api/dashboard` | Kennzahlen |
| GET  | `/api/units` | Wohnungen |
| GET/POST | `/api/bookings` | Buchungen lesen / erstellen (Doppelbuchungsschutz) |
| GET  | `/api/availability` | Verfügbarkeit prüfen |
| POST | `/api/tassa` | Kurtaxe berechnen |
| GET  | `/api/alloggiati` | Polizeimeldung als `.txt` |
| GET  | `/api/istat` | ISTAT-Statistik |
| POST | `/api/invoices` | Elektronische Rechnung (XML) |

> Hinweis: Codici Belfiore/Stato sind in `src/lib/codes.ts` exemplarisch hinterlegt; für den Produktivbetrieb die vollständigen offiziellen Tabellen der Polizia di Stato einbinden.
