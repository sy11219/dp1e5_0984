export type CapacityStatus = "green" | "yellow" | "red" | "gray";

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
  operationalStatus?: "ACTIVE" | "INACTIVE" | string;
  active?: boolean;
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
  scheduleStatus?: "SCHEDULED" | string;
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
    | "final_arrival"
    | "final_pickup"
    | "batch_complete"
    | "replan"
    | "flight_cancelled"
    | "flight_cancelled_pending"
    | "flight_cancel_redirected"
    | "simulation_paused"
    | "simulation_resumed"
    | "system_collapsed"
    | "scenario_cancelled"
    | "snapshot_baseline";
}

export interface Metrics {
  plannedShipments: number;
  shipments: number;
  processedShipments?: number;
  queuedShipments?: number;
  onTimeShipments: number;
  deliveredShipments?: number;
  deliveredOnTimeShipments?: number;
  unplannedShipments?: number;
  lateShipments?: number;
  inTransitShipments?: number;
  firstWarehouseShipments?: number;
  plannedBags: number;
  onTimeBags?: number;
  deliveredBags?: number;
  deliveredOnTimeBags?: number;
  unplannedBags?: number;
  lateBags?: number;
  inTransitBags?: number;
  firstWarehouseBags?: number;
  totalBags: number;
  usedFlights: number;
  pendingCancellations?: number;
  payloadShipments?: number;
  planningComplete?: boolean;
  deliveryCompleteWithinSimulation?: boolean;
  iterations: number;
  fitnessFinal: number;
  fitnessInitial: number;
  acceptedBySa: number;
  globalImprovements: number;
}

export interface Shipment {
  id: string;
  clientId: string;
  origin: string;
  destination: string;
  requestMinute: number;
  suitcases: number;
  planned: boolean;
  onTime: boolean;
  estimatedArrival: number;
  delayMinutes: number;
  flightIds: string[];
  flightLegs?: ShipmentFlightLeg[];
}

export interface ShipmentFlightLeg {
  flightId: string;
  origin: string;
  destination: string;
  dayOffset: number;
  departureMinute: number;
  arrivalMinute: number;
  absoluteDepartureMinute: number;
  absoluteArrivalMinute: number;
  assignedLoad: number;
  maxCapacity: number;
  utilization: number;
}

export interface CollapseInfo {
  reason: "WAREHOUSE_CAPACITY" | "DELIVERY_DEADLINE" | "" | string;
  minute: number;
  occurredAt: string;
  airport: string;
  shipmentId: string;
  expectedBags: number;
  deliveredBags: number;
  currentLoad: number;
  maxCapacity: number;
}

export interface SimulationData {
  simulationId?: string;
  ownerClientId?: string;
  controlToken?: string;
  scenario: string;
  status?: string;
  paused?: boolean;
  message?: string;
  days?: number;
  tick?: number;
  maxTick?: number;
  planningMaxTick?: number;
  startOffsetMinutes?: number;
  batchMinutes?: number;
  batchIntervalMs?: number;
  planningMode?: string;
  planningExecutionMs?: number;
  planningIntervalMs?: number;
  planningIntervalMinutes?: number;
  planningTimeScale?: number;
  planningWindowMinutes?: number;
  planningStable?: boolean;
  connectionWaitMinutes?: number;
  finalPickupWaitMinutes?: number;
  batchCount?: number;
  lastBatchStart?: number;
  lastBatchEnd?: number;
  lastBatchRuntimeMs?: number;
  visualStartTick?: number;
  visualEndTick?: number;
  visualStartedAt?: string;
  realtimeExecutionIntervalMs?: number;
  pendingCancellationCount?: number;
  cancelledFlightIds?: string[];
  collapse?: CollapseInfo;
  airports: Airport[];
  flights: Flight[];
  shipments: Shipment[];
  airportEvents: AirportEvent[];
  metrics: Metrics;
  lastPlanningMetrics?: Metrics;
  realStartedAt: string;
  realFinishedAt: string;
  simulationStartDateTime: string;
  simulationEndDateTime: string;
  simulationStoppedDateTime?: string;
  runtimeMs: number;
}

export interface ShipmentPage {
  page: number;
  pageSize: number;
  total: number;
  totalPages: number;
  items: Shipment[];
}

export type AirportLoads = Record<string, number>;

export interface MapInfoCard {
  type: "airport" | "flight" | "shipment";
  id?: string;
  title: string;
  subtitle?: string;
  rows: Array<[string, string | number]>;
}
