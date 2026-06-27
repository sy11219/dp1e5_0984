import type { Airport, Flight, ShipmentPage, SimulationData } from "../features/simulation/types";
import { SIMULATION_DAYS } from "../features/simulation/utils/constants";
import { api } from "./apiClient";

const clientTimeZone = () => Intl.DateTimeFormat().resolvedOptions().timeZone || "";
const BATCH_CLIENT_ID_KEY = "tasf.simulation5d.clientId";
const BATCH_CONTROL_TOKEN_PREFIX = "tasf.simulation5d.controlToken.";

export function getBatchClientId(): string {
  try {
    const existing = window.localStorage.getItem(BATCH_CLIENT_ID_KEY);
    if (existing) return existing;

    const generated =
      typeof crypto !== "undefined" && "randomUUID" in crypto
        ? crypto.randomUUID()
        : `${Date.now()}-${Math.random().toString(36).slice(2)}`;
    window.localStorage.setItem(BATCH_CLIENT_ID_KEY, generated);
    return generated;
  } catch {
    return "volatile-client";
  }
}

function batchTokenKey(simulationId: string): string {
  return `${BATCH_CONTROL_TOKEN_PREFIX}${simulationId}`;
}

export function getBatchControlToken(simulationId?: string): string {
  if (!simulationId) return "";
  try {
    return window.localStorage.getItem(batchTokenKey(simulationId)) || "";
  } catch {
    return "";
  }
}

function rememberBatchControlToken(data: SimulationData): void {
  if (!data.simulationId || !data.controlToken) return;
  try {
    window.localStorage.setItem(batchTokenKey(data.simulationId), data.controlToken);
  } catch {
    // Sin localStorage, la maquina queda como observadora tras recargar.
  }
}

export function ownsBatchSimulation(data: SimulationData | null | undefined): boolean {
  if (!data?.simulationId || !data.ownerClientId) return false;
  return data.ownerClientId === getBatchClientId() && Boolean(getBatchControlToken(data.simulationId));
}

// ── Simulación estática (un solo disparo, sin lotes) ─────────────────────────

export async function runSimulationRequest(
  startDate: string,
  days: number
): Promise<SimulationData> {
  const response = await api.post<SimulationData>("/simulations/alns", {
    startDate: startDate.replaceAll("-", ""),
    days,
    timeZone: clientTimeZone(),
  });
  return response.data;
}

// ── Simulación por lotes (SIMULACION_LOTES) ───────────────────────────────────

/**
 * Inicia una sesión de simulación por lotes.
 * Devuelve el estado inicial (tick = 0, status = "RUNNING").
 */
export async function startBatchSimulationRequest(
  startDate: string,
  days: number,
  startTime = "00:00",
  currentSimulationId?: string
): Promise<SimulationData> {
  const response = await api.post<SimulationData>("/simulations/batch/start", {
    startDate: startDate.replaceAll("-", ""),
    days,
    startTime,
    timeZone: clientTimeZone(),
    clientId: getBatchClientId(),
    controlToken: getBatchControlToken(currentSimulationId),
  });
  rememberBatchControlToken(response.data);
  return response.data;
}

export async function getCurrentBatchSimulationRequest(): Promise<SimulationData | null> {
  const response = await api.get<SimulationData | Record<string, never>>("/simulations/batch/current");
  const data = response.data as Partial<SimulationData>;
  return data.simulationId ? (data as SimulationData) : null;
}

/**
 * Avanza un lote de `steps` minutos simulados.
 * El backend ejecuta el ALNS y responde inmediatamente (sin sleep).
 * El frontend es responsable de la animación y de llamar de nuevo
 * cuando la animación del lote termina.
 */
export async function advanceBatchSimulationRequest(
  simulationId: string,
  steps: number,
  expectedTick: number
): Promise<SimulationData> {
  const response = await api.post<SimulationData>(
    `/simulations/batch/${simulationId}/advance`,
    {
      steps,
      expectedTick,
      clientId: getBatchClientId(),
      controlToken: getBatchControlToken(simulationId),
    }
  );
  rememberBatchControlToken(response.data);
  return response.data;
}

/**
 * Cancela un vuelo futuro dentro de la sesión por lotes y replanifica.
 */
export async function stopBatchSimulationRequest(
  simulationId: string
): Promise<void> {
  await api.post(`/simulations/batch/${simulationId}/stop`, {
    clientId: getBatchClientId(),
    controlToken: getBatchControlToken(simulationId),
  });
}

export async function pauseBatchSimulationRequest(
  simulationId: string,
  paused: boolean
): Promise<SimulationData> {
  const response = await api.post<SimulationData>(
    `/simulations/batch/${simulationId}/pause`,
    {
      paused,
      clientId: getBatchClientId(),
      controlToken: getBatchControlToken(simulationId),
    }
  );
  rememberBatchControlToken(response.data);
  return response.data;
}

export async function cancelBatchFlightRequest(
  simulationId: string,
  flightId: string
): Promise<SimulationData> {
  const response = await api.post<SimulationData>(
    `/simulations/batch/${simulationId}/cancel-flight`,
    { flightId }
  );
  return response.data;
}

// ── Tiempo real (sesión tick-a-tick) ─────────────────────────────────────────

export async function getBatchShipmentsPageRequest(
  simulationId: string,
  params: {
    page: number;
    pageSize: number;
    search?: string;
    origin?: string;
    destination?: string;
    status?: string;
  }
): Promise<ShipmentPage> {
  const query = new URLSearchParams();
  query.set("page", String(params.page));
  query.set("pageSize", String(params.pageSize));
  if (params.search?.trim()) query.set("search", params.search.trim());
  if (params.origin?.trim()) query.set("origin", params.origin.trim());
  if (params.destination?.trim()) query.set("destination", params.destination.trim());
  if (params.status?.trim()) query.set("status", params.status.trim());

  const response = await api.get<ShipmentPage>(
    `/simulations/batch/${simulationId}/shipments?${query.toString()}`
  );
  return response.data;
}

let realtimeOperationPromise: Promise<SimulationData> | null = null;

export async function runRealtimeOperationRequest(): Promise<SimulationData> {
  if (!realtimeOperationPromise) {
    realtimeOperationPromise = getCurrentRealtimeSessionRequest()
      .then((current) => current ?? startRealtimeSessionRequest())
      .catch((error) => {
        realtimeOperationPromise = null;
        throw error;
      });
  }
  return realtimeOperationPromise;
}

export async function refreshRealtimeOperationRequest(): Promise<SimulationData> {
  realtimeOperationPromise = null;
  return runRealtimeOperationRequest();
}

export async function startRealtimeSessionRequest(
  startDate?: string,
  days = SIMULATION_DAYS
): Promise<SimulationData> {
  const payload: { startDate?: string; days: number; timeZone: string } = {
    days,
    timeZone: clientTimeZone(),
  };
  if (startDate) payload.startDate = startDate.replaceAll("-", "");

  const response = await api.post<SimulationData>("/realtime/start", {
    ...payload,
  });
  return response.data;
}

export async function getCurrentRealtimeSessionRequest(): Promise<SimulationData | null> {
  const response = await api.get<SimulationData | Record<string, never>>("/realtime/current");
  const data = response.data as Partial<SimulationData>;
  return data.simulationId ? (data as SimulationData) : null;
}

export async function advanceRealtimeSessionRequest(
  simulationId: string,
  steps: number,
  expectedTick: number
): Promise<SimulationData> {
  const response = await api.post<SimulationData>(
    `/realtime/${simulationId}/tick`,
    { steps, expectedTick }
  );
  return response.data;
}

export async function cancelRealtimeFlightRequest(
  simulationId: string,
  flightId: string
): Promise<SimulationData> {
  const response = await api.post<SimulationData>(
    `/realtime/${simulationId}/cancel-flight`,
    { flightId }
  );
  return response.data;
}

export type AirportOperationalStatus = {
  code: string;
  status: "ACTIVE" | "INACTIVE" | string;
  active: boolean;
};

export async function getAirportsRequest(): Promise<Airport[]> {
  const response = await api.get<Airport[]>("/airports");
  return response.data;
}

export type AirportUpdatePayload = {
  city: string;
  country: string;
  continent: string;
  operationalStatus: "ACTIVE" | "INACTIVE";
  latitude: number;
  longitude: number;
  gmtOffset: number;
  maxCapacity: number;
};

export type AirportCreatePayload = Omit<AirportUpdatePayload, "operationalStatus"> & {
  code: string;
};

export async function createAirportRequest(
  payload: AirportCreatePayload
): Promise<Airport> {
  const response = await api.post<Airport>("/airports", payload);
  return response.data;
}

export async function updateAirportRequest(
  code: string,
  payload: AirportUpdatePayload
): Promise<Airport> {
  const response = await api.patch<Airport>(`/airports/${code}`, payload);
  return response.data;
}

export async function getFlightsRequest(): Promise<Flight[]> {
  const response = await api.get<Flight[]>("/flights");
  return response.data;
}

export type FlightPlanRecord = Flight & {
  flight_code: string;
  origin_airport_id: string;
  destination_airport_id: string;
  departure_time_local: string;
  arrival_time_local: string;
  departure_time_utc: string;
  arrival_time_utc: string;
  capacity: number;
  flightStatus?: string;
  scheduleStatus?: string;
};

export type FlightPlanUpdatePayload = {
  originAirportCode: string;
  destinationAirportCode: string;
  departureTimeLocal: string;
  arrivalTimeLocal: string;
  departureTimeUtc: string;
  arrivalTimeUtc: string;
  capacity: number;
  status: "SCHEDULED" | "CANCELED";
};

export type FlightPlanCreatePayload = Omit<FlightPlanUpdatePayload, "status">;

export async function getFlightPlansRequest(): Promise<FlightPlanRecord[]> {
  const response = await api.get<FlightPlanRecord[]>("/flights");
  return response.data;
}

export async function createFlightPlanRequest(
  payload: FlightPlanCreatePayload
): Promise<FlightPlanRecord> {
  const response = await api.post<FlightPlanRecord>("/flights", payload);
  return response.data;
}

export async function updateFlightPlanRequest(
  flightCode: string,
  payload: FlightPlanUpdatePayload
): Promise<FlightPlanRecord> {
  const response = await api.patch<FlightPlanRecord>(
    `/flights/${encodeURIComponent(flightCode)}`,
    payload
  );
  return response.data;
}

export type ShipmentCreatePayload = {
  originAirportCode: string;
  destinationAirportCode: string;
  departureDate: string;
  baggageCount: number;
  shipmentId: string;
};

function toUtcIsoIfLocalDateTime(value: string): string {
  if (!value || /(?:Z|[+-]\d{2}:\d{2})$/.test(value)) return value;
  const parsed = new Date(value);
  return Number.isFinite(parsed.getTime()) ? parsed.toISOString() : value;
}

export type ShipmentRecord = {
  shipment_code: string;
  origin_airport_code: string;
  destination_airport_code: string;
  baggage_count: number;
  registered_at: string;
  max_delivery_at: string;
  status: string;
};

export type ShipmentBatchCreatePayload = {
  originAirportCode: string;
  fileContent: string;
};

export type ShipmentBatchResult = {
  parsed: number;
  inserted: number;
  skipped: number;
};

export async function createShipmentRequest(
  payload: ShipmentCreatePayload
): Promise<ShipmentRecord> {
  const response = await api.post<ShipmentRecord>("/shipments", {
    ...payload,
    departureDate: toUtcIsoIfLocalDateTime(payload.departureDate),
  });
  return response.data;
}

function toBase64(value: string): string {
  const bytes = new TextEncoder().encode(value);
  let binary = "";
  bytes.forEach((byte) => {
    binary += String.fromCharCode(byte);
  });
  return btoa(binary);
}

export async function createShipmentBatchRequest(
  payload: ShipmentBatchCreatePayload
): Promise<ShipmentBatchResult> {
  const response = await api.post<ShipmentBatchResult>("/shipments/batch", {
    originAirportCode: payload.originAirportCode,
    fileContentBase64: toBase64(payload.fileContent),
  });
  return response.data;
}

export async function getAirportStatus(
  code: string
): Promise<AirportOperationalStatus> {
  const response = await api.get<AirportOperationalStatus>(
    `/airports/${code}/status`
  );
  return response.data;
}

export async function updateAirportStatus(
  code: string,
  active: boolean
): Promise<AirportOperationalStatus> {
  const response = await api.patch<AirportOperationalStatus>(
    `/airports/${code}/status`,
    { active }
  );
  return response.data;
}
