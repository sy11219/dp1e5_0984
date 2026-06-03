import { api } from "./apiClient";

// ── Upload de archivos ─────────────────────────────────────────────────────────

export async function uploadRequest(
    flightFile: File | null,
    airportFile: File | null,
    shipmentFiles: File[]
): Promise<void> {

  const formData = new FormData();

  if (flightFile) formData.append("flightFile", flightFile);
  if (airportFile) formData.append("airportFile", airportFile);
  shipmentFiles.forEach((file, index) =>
    formData.append(`shipmentFile${index + 1}`, file)
  );

  const response = await api.post("/upload", formData, {
    headers: { "Content-Type": "multipart/form-data" },
  });

  return response.data;
}