import { useEffect, useState } from "react";
import type { Airport } from "../types";

const STORAGE_KEY = "tasf.assignedAirportTime";
const CHANGE_EVENT = "tasf.assignedAirportTime.change";

export type AssignedAirportTime = Pick<Airport, "code" | "city" | "gmtOffset">;

export function readAssignedAirportTime(): AssignedAirportTime | null {
  try {
    const raw = window.localStorage.getItem(STORAGE_KEY);
    if (!raw) return null;
    const parsed = JSON.parse(raw) as Partial<AssignedAirportTime>;
    if (!parsed.code || typeof parsed.gmtOffset !== "number") return null;
    return {
      code: parsed.code,
      city: parsed.city || "",
      gmtOffset: parsed.gmtOffset,
    };
  } catch {
    return null;
  }
}

export function writeAssignedAirportTime(airport: AssignedAirportTime | null): void {
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
