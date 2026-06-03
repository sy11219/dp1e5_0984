import axios from "axios";
import type { SimulationData } from "../features/simulation/types";

const api = axios.create({
  baseURL: "/api",
});

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

export type AirportOperationalStatus = {
  code: string;
  status: "ACTIVE" | "INACTIVE" | string;
  active: boolean;
};

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
