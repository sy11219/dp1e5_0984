import { useEffect, useState } from "react";
import { updateAirportStatus } from "../../../../api/simulationApi";
import type { Airport } from "../../types";
import { capacityStatus } from "../../utils/calculations";

interface MetricProps {
  label: string;
  value: string | number;
  sub: string | number;
}

function Metric({ label, value, sub }: MetricProps) {
  return (
    <div className="metric flex flex-col text-sm">
      <span className="text-xs text-muted-foreground">{label}</span>
      <strong className="text-sm">{value}</strong>
      <span className="text-xs text-muted-foreground">{sub}</span>
    </div>
  );
}

interface AirportDetailProps {
  airport: Airport;
  load: number;
  peakLoad?: number;
  peakLabel?: string;
  showMetrics?: boolean;
  onStatusUpdated?: (code: string, active: boolean, status: string) => void;
}

function formatPercent(value: number): string {
  return `${(value * 100).toLocaleString("es-PE", {
    minimumFractionDigits: 2,
    maximumFractionDigits: 2,
  })}%`;
}

function statusLabel(status: ReturnType<typeof capacityStatus>): string {
  switch (status) {
    case "gray":
      return "Vacío";
    case "green":
      return "Normal";
    case "yellow":
      return "Atención";
    case "red":
      return "Crítico";
    default:
      return "Sin datos";
  }
}

export function AirportDetail({
  airport,
  load,
  peakLoad,
  peakLabel = "Pico registrado",
  showMetrics = true,
  onStatusUpdated,
}: AirportDetailProps) {
  const utilization = airport.maxCapacity ? load / airport.maxCapacity : 0;
  const effectivePeakLoad = Math.max(load, peakLoad ?? airport.peakLoad ?? 0);
  const peakUtilization = airport.maxCapacity ? effectivePeakLoad / airport.maxCapacity : 0;
  const status = capacityStatus(utilization);
  const [active, setActive] = useState(airport.active ?? true);
  const [savingStatus, setSavingStatus] = useState(false);
  const [statusError, setStatusError] = useState("");

  useEffect(() => {
    setActive(airport.active ?? airport.operationalStatus !== "INACTIVE");
    setStatusError("");
  }, [airport.active, airport.code, airport.operationalStatus]);

  const handleStatusChange = async () => {
    const nextActive = !active;
    setActive(nextActive);
    setSavingStatus(true);
    setStatusError("");

    try {
      const response = await updateAirportStatus(airport.code, nextActive);
      setActive(response.active);
      onStatusUpdated?.(response.code, response.active, response.status);
    } catch {
      setActive(!nextActive);
      setStatusError("No se pudo actualizar en BD.");
    } finally {
      setSavingStatus(false);
    }
  };

  return (
    <>
      <div className="airport-status-control flex items-center justify-between gap-3 text-sm">
        <div className="flex flex-col">
          <span className="text-xs text-muted-foreground">Estado operativo</span>
          <strong className="text-sm">{active ? "Activo" : "Inactivo"}</strong>
        </div>
        <button
          type="button"
          role="switch"
          aria-checked={active}
          className={`switch ${active ? "on" : ""}`}
          onClick={handleStatusChange}
          disabled={savingStatus}
          title={active ? "Desactivar aeropuerto" : "Activar aeropuerto"}
          style={{ padding: 6 }}
        >
          <span />
        </button>
      </div>

      {statusError && <div className="inline-error text-xs text-destructive">{statusError}</div>}

      {showMetrics && (
        <div className="metrics grid grid-cols-2 gap-2 mt-2 text-sm">
          <Metric label="Carga actual" value={load} sub={`cap. ${airport.maxCapacity}`} />
          <Metric label="Uso actual" value={formatPercent(utilization)} sub={statusLabel(status)} />
          <Metric label={peakLabel} value={effectivePeakLoad} sub={formatPercent(peakUtilization)} />
          <Metric label="Ubicación" value={airport.country} sub={airport.continent} />
        </div>
      )}
    </>
  );
}
