import { useEffect, useState } from "react";
import type { Airport } from "../types";

const STORAGE_KEY = "tasf.assignedAirportTime";
const CHANGE_EVENT = "tasf.assignedAirportTime.change";

export type AssignedAirportTime = Pick<Airport, "code" | "city" | "gmtOffset">;

const TIME_ZONE_AIRPORT_CODES: Record<string, string[]> = {
  "America/Lima": ["SPJC", "SPIM"],
  "America/Bogota": ["SKBO"],
  "America/Santiago": ["SCEL"],
};

type StoredAssignedAirportTime = AssignedAirportTime & {
  timeZone?: string;
  source?: "auto" | "manual";
};

export function readAssignedAirportTime(): AssignedAirportTime | null {
  const parsed = readStoredAssignedAirportTime();
  if (!parsed) return null;
  return {
    code: parsed.code,
    city: parsed.city || "",
    gmtOffset: parsed.gmtOffset,
  };
}

function readStoredAssignedAirportTime(): StoredAssignedAirportTime | null {
  try {
    const raw = window.localStorage.getItem(STORAGE_KEY);
    if (!raw) return null;
    const parsed = JSON.parse(raw) as Partial<StoredAssignedAirportTime>;
    if (!parsed.code || typeof parsed.gmtOffset !== "number") return null;
    return {
      code: parsed.code,
      city: parsed.city || "",
      gmtOffset: parsed.gmtOffset,
      timeZone: parsed.timeZone,
      source: parsed.source,
    };
  } catch {
    return null;
  }
}

export function writeAssignedAirportTime(airport: AssignedAirportTime | StoredAssignedAirportTime | null): void {
  try {
    if (airport) {
      window.localStorage.setItem(STORAGE_KEY, JSON.stringify(airport));
    } else {
      window.localStorage.removeItem(STORAGE_KEY);
    }
    window.dispatchEvent(new CustomEvent(CHANGE_EVENT));
  } catch {
    // Sin localStorage, la asignacion queda limitada a la vista actual.
  }
}

export function assignAirportTimeFromSystemTimeZone(airports: Airport[]): AssignedAirportTime | null {
  const current = readStoredAssignedAirportTime();
  if (current?.source === "manual") return current;

  const timeZone = getSystemTimeZone();
  if (!timeZone || !airports.length) return null;

  const offset = getCurrentOffsetHours(timeZone);
  if (offset === null || !Number.isInteger(offset)) return null;

  const match = findBestAirportForTimeZone(airports, timeZone, offset);
  if (!match) return null;

  const assigned: StoredAssignedAirportTime = {
    code: match.code,
    city: match.city || "",
    gmtOffset: match.gmtOffset ?? offset,
    timeZone,
    source: "auto",
  };
  writeAssignedAirportTime(assigned);
  return assigned;
}

export function assignManualAirportTime(airport: AssignedAirportTime): AssignedAirportTime {
  const assigned: StoredAssignedAirportTime = {
    code: airport.code,
    city: airport.city || "",
    gmtOffset: airport.gmtOffset ?? 0,
    source: "manual",
  };
  writeAssignedAirportTime(assigned);
  return assigned;
}

function getSystemTimeZone(): string {
  try {
    return Intl.DateTimeFormat().resolvedOptions().timeZone || "";
  } catch {
    return "";
  }
}

function getCurrentOffsetHours(timeZone: string): number | null {
  const now = new Date();
  const shortOffset = parseShortOffset(timeZone, now);
  if (shortOffset !== null) return shortOffset;

  try {
    const parts = new Intl.DateTimeFormat("en-US", {
      timeZone,
      year: "numeric",
      month: "2-digit",
      day: "2-digit",
      hour: "2-digit",
      minute: "2-digit",
      second: "2-digit",
      hour12: false,
    }).formatToParts(now);
    const value = (type: string) => Number(parts.find((part) => part.type === type)?.value);
    const asUtc = Date.UTC(
      value("year"),
      value("month") - 1,
      value("day"),
      value("hour"),
      value("minute"),
      value("second")
    );
    return Math.round((asUtc - now.getTime()) / 3_600_000);
  } catch {
    return null;
  }
}

function parseShortOffset(timeZone: string, date: Date): number | null {
  try {
    const parts = new Intl.DateTimeFormat("en-US", {
      timeZone,
      timeZoneName: "shortOffset",
      hour: "2-digit",
    }).formatToParts(date);
    const label = parts.find((part) => part.type === "timeZoneName")?.value || "";
    const match = label.match(/GMT(?:(\+|-)(\d{1,2})(?::?(\d{2}))?)?/i);
    if (!match) return null;
    if (!match[1]) return 0;
    const sign = match[1] === "-" ? -1 : 1;
    const hours = Number(match[2] || 0);
    const minutes = Number(match[3] || 0);
    return sign * (hours + minutes / 60);
  } catch {
    return null;
  }
}

function findBestAirportForTimeZone(
  airports: Airport[],
  timeZone: string,
  offset: number
): Airport | null {
  const explicitMatch = findExplicitTimeZoneAirport(airports, timeZone);
  if (explicitMatch) return explicitMatch;

  const candidates = airports
    .filter((airport) => airport.gmtOffset === offset)
    .sort((a, b) => a.code.localeCompare(b.code));
  if (!candidates.length) return null;

  const hints = timeZoneHints(timeZone);
  const scored = candidates
    .map((airport) => ({ airport, score: scoreAirport(airport, hints) }))
    .sort((a, b) => b.score - a.score || a.airport.code.localeCompare(b.airport.code));

  return scored[0]?.airport || null;
}

function findExplicitTimeZoneAirport(airports: Airport[], timeZone: string): Airport | null {
  const codes = TIME_ZONE_AIRPORT_CODES[timeZone];
  if (!codes?.length) return null;

  for (const code of codes) {
    const airport = airports.find((item) => item.code === code);
    if (airport) return airport;
  }

  return null;
}

function timeZoneHints(timeZone: string): string[] {
  const segments = timeZone.split("/").flatMap((segment) => segment.split(/[_-]/));
  const last = timeZone.split("/").at(-1) || "";
  return Array.from(new Set([last, ...segments].map(normalize).filter(Boolean)));
}

function scoreAirport(airport: Airport, hints: string[]): number {
  const city = normalize(airport.city);
  const country = normalize(airport.country);
  let score = 0;

  for (const hint of hints) {
    if (city === hint) score += 100;
    else if (city.includes(hint) || hint.includes(city)) score += 40;
    if (country === hint) score += 30;
    else if (country.includes(hint) || hint.includes(country)) score += 10;
  }

  return score;
}

function normalize(value: string | undefined): string {
  return (value || "")
    .normalize("NFD")
    .replace(/[\u0300-\u036f]/g, "")
    .toLowerCase()
    .replace(/[^a-z0-9]/g, "");
}

export function useAssignedAirportTime(): AssignedAirportTime | null {
  const [assignedAirport, setAssignedAirport] = useState(readAssignedAirportTime);

  useEffect(() => {
    const refresh = () => setAssignedAirport(readAssignedAirportTime());
    window.addEventListener(CHANGE_EVENT, refresh);
    window.addEventListener("storage", refresh);
    return () => {
      window.removeEventListener(CHANGE_EVENT, refresh);
      window.removeEventListener("storage", refresh);
    };
  }, []);

  return assignedAirport;
}
