import type { Flight, Shipment } from "../types";

export type AirportShipment = {
  shipment: Shipment;
  role: "origin" | "destination" | "transfer";
};

export type AirportFlight = {
  flight: Flight;
  direction: "incoming" | "outgoing";
  counterpart: string;
  load: number;
  time: number;
};

/**
 * Obtiene los envíos que pasan por un aeropuerto.
 * - Origen: shipment.origin === airportCode
 * - Destino: shipment.destination === airportCode
 * - Escala: el aeropuerto aparece como destino/origen de algún vuelo intermedio
 */
export function getShipmentsForAirport(
  shipments: Shipment[],
  flights: Flight[],
  airportCode: string
): AirportShipment[] {
  // Crear un mapa de vuelos por ID para búsqueda rápida
  const flightMap = new Map(flights.map((f) => [f.id, f]));

  return shipments
    .map((shipment) => {
      // Determinar el rol
      if (shipment.origin === airportCode) {
        return { shipment, role: "origin" as const };
      }
      if (shipment.destination === airportCode) {
        return { shipment, role: "destination" as const };
      }

      // Verificar si es escala: el aeropuerto debe aparecer en la ruta de los vuelos
      const isTransfer = shipment.flightIds.some((flightId) => {
        const flight = flightMap.get(flightId);
        if (!flight) return false;
        return flight.origin === airportCode || flight.destination === airportCode;
      });

      if (isTransfer) {
        return { shipment, role: "transfer" as const };
      }

      return null;
    })
    .filter((item): item is AirportShipment => item !== null);
}

/**
 * Obtiene vuelos entrantes y salientes de un aeropuerto.
 */
export function getFlightsForAirport(
  flights: Flight[],
  airportCode: string,
  simMinute: number
): AirportFlight[] {
  const results: AirportFlight[] = [];

  for (const flight of flights) {
    if (flight.destination === airportCode) {
      results.push({
        flight,
        direction: "incoming",
        counterpart: flight.origin,
        load: flight.assignedLoad,
        time: flight.absoluteArrivalMinute,
      });
    }

    if (flight.origin === airportCode) {
      results.push({
        flight,
        direction: "outgoing",
        counterpart: flight.destination,
        load: flight.assignedLoad,
        time: flight.absoluteDepartureMinute,
      });
    }
  }

  // 🔹 Filtrar solo vuelos futuros
  const upcoming = results.filter((f) => f.time > simMinute);

  // 🔹 Ordenar de más pronto a más próximo
  upcoming.sort((a, b) => a.time - b.time);

  return upcoming;
}


export function getNextFlightByAirport(
  flights: Flight[],
  simMinute: number
): Map<string, AirportFlight> {
  const nextByAirport = new Map<string, AirportFlight>();

  const setIfEarlier = (airportCode: string, nextFlight: AirportFlight) => {
    const current = nextByAirport.get(airportCode);
    if (!current || nextFlight.time < current.time) {
      nextByAirport.set(airportCode, nextFlight);
    }
  };

  for (const flight of flights) {
    if (flight.absoluteArrivalMinute > simMinute) {
      setIfEarlier(flight.destination, {
        flight,
        direction: "incoming",
        counterpart: flight.origin,
        load: flight.assignedLoad,
        time: flight.absoluteArrivalMinute,
      });
    }

    if (flight.absoluteDepartureMinute > simMinute) {
      setIfEarlier(flight.origin, {
        flight,
        direction: "outgoing",
        counterpart: flight.destination,
        load: flight.assignedLoad,
        time: flight.absoluteDepartureMinute,
      });
    }
  }

  return nextByAirport;
}

export function getNextFlightForAirport(
  flights: Flight[],
  airportCode: string,
  simMinute: number
): AirportFlight | null {
  return getNextFlightByAirport(flights, simMinute).get(airportCode) ?? null;
}
