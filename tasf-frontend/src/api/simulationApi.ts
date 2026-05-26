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
