import axios from "axios";

const api = axios.create({
  baseURL: "/api",
});

export async function runSimulationRequest(
  startDate: string,
  days: number
) {
  const response = await api.post("/simulations/alns", {
    startDate: startDate.replaceAll("-", ""),
    days,
  });

  return response.data;
}