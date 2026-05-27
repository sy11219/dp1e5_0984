import axios from "axios";
import type { SimulationData } from "../features/simulation/types";
import { DEFAULT_START_DATE } from "../features/simulation/utils/constants";

const api = axios.create({
  baseURL: "/api",
});

let realtimeOperationPromise: Promise<SimulationData> | null = null;

export async function runSimulationRequest(
  startDate: string,
  days: number
): Promise<SimulationData> {
  const response = await api.post<SimulationData>("/simulations/alns", {
    startDate: startDate.replaceAll("-", ""),
    days,
  });

  return response.data;
}

export async function runRealtimeOperationRequest(): Promise<SimulationData> {
  if (!realtimeOperationPromise) {
    realtimeOperationPromise = runSimulationRequest(DEFAULT_START_DATE, 3).catch(
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
  days = 3
): Promise<SimulationData> {
  const response = await api.post<SimulationData>("/realtime/start", {
    startDate: startDate.replaceAll("-", ""),
    days,
  });

  return response.data;
}

export async function advanceRealtimeSessionRequest(
  simulationId: string,
  steps: number
): Promise<SimulationData> {
  const response = await api.post<SimulationData>(
    `/realtime/${simulationId}/tick`,
    { steps }
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
