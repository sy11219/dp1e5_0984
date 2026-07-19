import type { Flight, Shipment } from "../types";

export type ShipmentRouteLeg = {
  flightId: string;
  flight?: Flight;
  origin: string;
  destination: string;
  absoluteDepartureMinute?: number;
  absoluteArrivalMinute?: number;
  assignedLoad?: number;
  maxCapacity?: number;
  waitBeforeMinutes?: number;
  transferAirport?: string;
};

function parseFlightId(flightId: string) {
  const [origin, destination] = flightId.split("-");
  return {
    origin: origin || "???",
    destination: destination || "???",
  };
}

export function resolveShipmentRoute(
  shipment: Shipment | null | undefined,
  flights: Flight[]
): ShipmentRouteLeg[] {
  if (!shipment || !shipment.flightIds.length) return [];

  if (shipment.flightLegs?.length) {
    let previousArrival: number | null = null;
    let previousDestination: string | null = null;

    return shipment.flightLegs.map((leg) => {
      const waitBeforeMinutes =
        previousArrival !== null
          ? Math.max(0, leg.absoluteDepartureMinute - previousArrival)
          : undefined;
      const resolved: ShipmentRouteLeg = {
        flightId: leg.flightId,
        origin: leg.origin,
        destination: leg.destination,
        absoluteDepartureMinute: leg.absoluteDepartureMinute,
        absoluteArrivalMinute: leg.absoluteArrivalMinute,
        assignedLoad: leg.assignedLoad,
        maxCapacity: leg.maxCapacity,
        waitBeforeMinutes,
        transferAirport: previousDestination ?? undefined,
      };
      previousArrival = leg.absoluteArrivalMinute;
      previousDestination = leg.destination;
      return resolved;
    });
  }

  const flightsById = new Map<string, Flight[]>();
  for (const flight of flights) {
    const group = flightsById.get(flight.id);
    if (group) {
      group.push(flight);
    } else {
      flightsById.set(flight.id, [flight]);
    }
  }
  for (const group of flightsById.values()) {
    group.sort((a, b) => a.absoluteDepartureMinute - b.absoluteDepartureMinute);
  }

  const used = new Set<string>();
  let readyMinute = shipment.requestMinute;
  let previousArrival: number | null = null;
  let previousDestination: string | null = null;

  return shipment.flightIds.map((flightId) => {
    const candidates = flightsById.get(flightId) ?? [];
    const flight =
      candidates.find((candidate) => {
        const key = `${candidate.id}@${candidate.absoluteDepartureMinute}`;
        return candidate.absoluteDepartureMinute >= readyMinute && !used.has(key);
      }) ??
      candidates.find((candidate) => !used.has(`${candidate.id}@${candidate.absoluteDepartureMinute}`)) ??
      candidates[0];
    const parsed = parseFlightId(flightId);

    if (flight) {
      used.add(`${flight.id}@${flight.absoluteDepartureMinute}`);
    }

    const waitBeforeMinutes =
      flight && previousArrival !== null
        ? Math.max(0, flight.absoluteDepartureMinute - previousArrival)
        : undefined;

    const leg: ShipmentRouteLeg = {
      flightId,
      flight,
      origin: flight?.origin ?? parsed.origin,
      destination: flight?.destination ?? parsed.destination,
      absoluteDepartureMinute: flight?.absoluteDepartureMinute,
      absoluteArrivalMinute: flight?.absoluteArrivalMinute,
      assignedLoad: flight?.assignedLoad,
      maxCapacity: flight?.maxCapacity,
      waitBeforeMinutes,
      transferAirport: previousDestination ?? undefined,
    };

    if (flight) {
      readyMinute = flight.absoluteArrivalMinute;
      previousArrival = flight.absoluteArrivalMinute;
      previousDestination = flight.destination;
    }

    return leg;
  });
}
