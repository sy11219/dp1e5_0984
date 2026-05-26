export type CapacityStatus = "green" | "yellow" | "red";

export interface Airport {
  code: string;
  city: string;
  country: string;
  continent: string;
  latitude: number;
  longitude: number;
  gmtOffset?: number;
  maxCapacity: number;
  peakLoad: number;
  finalLoad?: number;
  utilization: number;
  status?: CapacityStatus;
}

export interface Flight {
  id: string;
  origin: string;
  destination: string;
  departureMinute: number;
  arrivalMinute: number;
  dayOffset: number;
  status: CapacityStatus;
  utilization: number;
  assignedLoad: number;
  maxCapacity: number;
  absoluteDepartureMinute: number;
  absoluteArrivalMinute: number;
}

export type ActiveFlight = Flight & {
  progress: number;
};

export interface AirportEvent {
  minute: number;
  airport: string;
  delta: number;
  type?:
    | "shipment_created"
    | "flight_departure"
    | "connection_arrival"
    | "connection_departure"
    | "final_arrival";
}

export interface Metrics {
  plannedShipments: number;
  shipments: number;
  onTimeShipments: number;
  plannedBags: number;
  totalBags: number;
  usedFlights: number;
  iterations: number;
  fitnessFinal: number;
  fitnessInitial: number;
  acceptedBySa: number;
  globalImprovements: number;
}

export interface SimulationData {
  simulationId?: string;
  scenario: string;
  status?: string;
  days?: number;
  airports: Airport[];
  flights: Flight[];
  airportEvents: AirportEvent[];
  metrics: Metrics;
  realStartedAt: string;
  realFinishedAt: string;
  simulationStartDateTime: string;
  simulationEndDateTime: string;
  runtimeMs: number;
}

export type AirportLoads = Record<string, number>;

export interface MapInfoCard {
  type: "airport" | "flight";
  title: string;
  subtitle?: string;
  rows: Array<[string, string | number]>;
}
