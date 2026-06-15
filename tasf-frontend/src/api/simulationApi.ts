import type { Airport, Flight, SimulationData } from "../features/simulation/types";
import { DEFAULT_START_DATE, SIMULATION_DAYS } from "../features/simulation/utils/constants";
import { api } from "./apiClient";

const clientTimeZone = () => Intl.DateTimeFormat().resolvedOptions().timeZone || "";

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
  startTime = "00:00"
): Promise<SimulationData> {
  const response = await api.post<SimulationData>("/simulations/batch/start", {
    startDate: startDate.replaceAll("-", ""),
    days,
    startTime,
    timeZone: clientTimeZone(),
  });
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
    { steps, expectedTick }
  );
  return response.data;
}

/**
 * Cancela un vuelo futuro dentro de la sesión por lotes y replanifica.
 */
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

let realtimeOperationPromise: Promise<SimulationData> | null = null;

export async function runRealtimeOperationRequest(): Promise<SimulationData> {
  if (!realtimeOperationPromise) {
    realtimeOperationPromise = runSimulationRequest(DEFAULT_START_DATE, SIMULATION_DAYS).catch(
      (error) => {
        realtimeOperationPromise = null;
        throw error;
      }
    );
  }
  return realtimeOperationPromise;
}

export async function refreshRealtimeOperationRequest(): Promise<SimulationData> {
  realtimeOperationPromise = null;
  return runRealtimeOperationRequest();
}

export async function startRealtimeSessionRequest(
  startDate = DEFAULT_START_DATE,
  days = SIMULATION_DAYS
): Promise<SimulationData> {
  const response = await api.post<SimulationData>("/realtime/start", {
    startDate: startDate.replaceAll("-", ""),
    days,
    timeZone: clientTimeZone(),
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

export async function getFlightsRequest(): Promise<Flight[]> {
  const response = await api.get<Flight[]>("/flights");
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
