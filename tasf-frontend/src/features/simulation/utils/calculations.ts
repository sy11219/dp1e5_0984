import type { ActiveFlight, AirportLoads, SimulationData } from "../types";

export function capacityStatus(utilization: number): "green" | "yellow" | "red" {
  if (utilization < 0.7) return "green";
  if (utilization < 0.9) return "yellow";
  return "red";
}

export function clamp(value: number, min: number, max: number): number {
  return Math.min(max, Math.max(min, value));
}

export function computeActiveFlights(
  data: SimulationData | null,
  minute: number
): ActiveFlight[] {
  if (!data) return [];
  return data.flights
    .filter(
      (flight) =>
        minute >= flight.absoluteDepartureMinute &&
        minute <= flight.absoluteArrivalMinute
    )
    .map((flight) => ({
      ...flight,
      progress: clamp(
        (minute - flight.absoluteDepartureMinute) /
          Math.max(1, flight.absoluteArrivalMinute - flight.absoluteDepartureMinute),
        0,
        1
      ),
    }));
}

export function computeAirportLoads(
  data: SimulationData | null,
  minute: number
): AirportLoads {
  if (!data) return {};
  const loads = Object.fromEntries(
    data.airports.map((airport) => [airport.code, 0])
  );
  for (const event of data.airportEvents) {
    if (event.minute > minute) break;
    loads[event.airport] = Math.max(0, (loads[event.airport] || 0) + event.delta);
  }
  return loads;
}

export function bearingDegrees(
  lat1: number,
  lon1: number,
  lat2: number,
  lon2: number
): number {
  const toRad = (value: number) => (value * Math.PI) / 180;
  const toDeg = (value: number) => (value * 180) / Math.PI;
  const phi1 = toRad(lat1);
  const phi2 = toRad(lat2);
  const deltaLon = toRad(lon2 - lon1);
  const y = Math.sin(deltaLon) * Math.cos(phi2);
  const x =
    Math.cos(phi1) * Math.sin(phi2) -
    Math.sin(phi1) * Math.cos(phi2) * Math.cos(deltaLon);
  return (toDeg(Math.atan2(y, x)) + 360) % 360;
}
