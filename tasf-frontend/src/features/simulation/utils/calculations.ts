import type { ActiveFlight, AirportEvent, AirportLoads, SimulationData } from "../types";
import { getCapacityThresholds } from "./capacityThresholds";

export function capacityStatus(utilization: number): "green" | "yellow" | "red" | "gray" {
  const thresholds = getCapacityThresholds();
  const greenLimit = thresholds.green / 100;
  const yellowLimit = thresholds.yellow / 100;
  const grayLimit = thresholds.gray / 100;
  const EPS = 0.001; // treat utilization below 0.1% as effectively zero
  if (utilization <= Math.max(EPS, grayLimit)) return "gray";
  if (utilization < greenLimit) return "green";
  if (utilization < yellowLimit) return "yellow";
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
  const active: ActiveFlight[] = [];

  for (const flight of data.flights) {
    //if (flight.assignedLoad <= 0) continue;
    if (flight.scheduleStatus?.toUpperCase().startsWith("CANCEL")) continue;
    if (minute < flight.absoluteDepartureMinute) continue;
    if (minute > flight.absoluteArrivalMinute) continue;

    active.push({
      ...flight,
      progress: clamp(
        (minute - flight.absoluteDepartureMinute) /
        Math.max(1, flight.absoluteArrivalMinute - flight.absoluteDepartureMinute),
        0,
        1
      ),
    });
  }

  return active;
}

export function computeAirportLoads(
  data: SimulationData | null,
  minute: number
): AirportLoads {
  return computeAirportLoadMetrics(data, minute).loads;
}

function airportEventPriority(event: AirportEvent): number {
  switch (event.type) {
    case "snapshot_baseline":
      return 0;
    case "flight_departure":
    case "connection_departure":
    case "final_pickup":
      return 1;
    case "shipment_created":
    case "connection_arrival":
    case "final_arrival":
      return 2;
    default:
      return 3;
  }
}

function compareAirportEvents(a: AirportEvent, b: AirportEvent): number {
  if (a.minute !== b.minute) return a.minute - b.minute;
  if (a.airport !== b.airport) return a.airport.localeCompare(b.airport);
  return airportEventPriority(a) - airportEventPriority(b);
}

const orderedAirportEventsCache = new WeakMap<AirportEvent[], AirportEvent[]>();

function getOrderedAirportEvents(events: AirportEvent[]): AirportEvent[] {
  const cached = orderedAirportEventsCache.get(events);
  if (cached) return cached;

  let ordered = true;
  for (let i = 1; i < events.length; i += 1) {
    if (compareAirportEvents(events[i - 1], events[i]) > 0) {
      ordered = false;
      break;
    }
  }

  const result = ordered ? events : [...events].sort(compareAirportEvents);
  orderedAirportEventsCache.set(events, result);
  return result;
}

export function computeAirportPeakLoads(
  data: SimulationData | null,
  minute: number
): AirportLoads {
  return computeAirportLoadMetrics(data, minute).peakLoads;
}

export interface AirportLoadMetrics {
  loads: AirportLoads;
  peakLoads: AirportLoads;
}

export function computeAirportLoadMetrics(
  data: SimulationData | null,
  minute: number,
  peakStartMinute = 0
): AirportLoadMetrics {
  if (!data) return { loads: {}, peakLoads: {} };

  const loads: AirportLoads = {};
  const peaks: AirportLoads = {};
  for (const airport of data.airports) {
    loads[airport.code] = 0;
    peaks[airport.code] = data.snapshotLimited
      ? Math.max(0, airport.historicalPeakLoad ?? 0)
      : 0;
  }

  const trackingStart = Math.max(0, peakStartMinute);
  let trackingPeaks = trackingStart === 0;
  for (const event of getOrderedAirportEvents(data.airportEvents)) {
    if (event.minute > minute) break;

    if (!trackingPeaks && event.minute >= trackingStart) {
      for (const airport of data.airports) {
        peaks[airport.code] = Math.max(peaks[airport.code] || 0, loads[airport.code] || 0);
      }
      trackingPeaks = true;
    }

    const next = Math.max(0, (loads[event.airport] || 0) + event.delta);
    loads[event.airport] = next;
    if (trackingPeaks && event.airport in peaks) {
      peaks[event.airport] = Math.max(peaks[event.airport] || 0, next);
    }
  }

  if (!trackingPeaks) {
    for (const airport of data.airports) {
      peaks[airport.code] = Math.max(peaks[airport.code] || 0, loads[airport.code] || 0);
    }
  }

  return { loads, peakLoads: peaks };
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
