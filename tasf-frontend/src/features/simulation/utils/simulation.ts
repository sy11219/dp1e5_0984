import type { SimulationResponse } from "../types/SimulationResponse";

export function computeActiveFlights(
  data: SimulationResponse | null,
  minute: number
) {
  if (!data) return [];

  return data.flights
    .filter(
      (flight) =>
        minute >= flight.departureMinute &&
        minute <= flight.arrivalMinute
    )
    .map((flight) => ({
      ...flight,

      progress:
        (minute - flight.departureMinute) /
        Math.max(
          1,
          flight.arrivalMinute -
            flight.departureMinute
        ),
    }));
}

export function computeAirportLoads(
  data: SimulationResponse | null,
  minute: number
) {
  if (!data) return {};

  const loads: Record<string, number> = {};

  for (const airport of data.airports) {
    loads[airport.code] = 0;
  }

  for (const event of data.airportEvents) {
    if (event.minute > minute) break;

    loads[event.airport] = Math.max(
      0,
      (loads[event.airport] || 0) + event.delta
    );
  }

  return loads;
}