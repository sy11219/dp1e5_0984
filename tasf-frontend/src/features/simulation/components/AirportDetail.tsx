import { useEffect, useState } from "react";
import { getAirportStatus, updateAirportStatus } from "../../../api/simulationApi";
import type { Airport } from "../types";
import { capacityStatus } from "../utils/calculations";

interface MetricProps {
  label: string;
  value: string | number;
  sub: string | number;
}

function Metric({ label, value, sub }: MetricProps) {
  return (
    <div className="metric">
      <span>{label}</span>
      <strong>{value}</strong>
      <span>{sub}</span>
    </div>
  );
}

interface AirportDetailProps {
  airport: Airport;
  load: number;
  peakLabel?: string;
}

export function AirportDetail({
  airport,
  load,
  peakLabel = "Pico ALNS",
}: AirportDetailProps) {
  const utilization = airport.maxCapacity ? load / airport.maxCapacity : 0;
  const status = capacityStatus(utilization);
  const [active, setActive] = useState(true);
  const [loadingStatus, setLoadingStatus] = useState(false);
  const [savingStatus, setSavingStatus] = useState(false);
  const [statusError, setStatusError] = useState("");

  useEffect(() => {
    let cancelled = false;
    setLoadingStatus(true);
    setStatusError("");

    getAirportStatus(airport.code)
      .then((response) => {
        if (!cancelled) setActive(response.active);
      })
      .catch(() => {
        if (!cancelled) setStatusError("No se pudo leer el estado.");
      })
      .finally(() => {
        if (!cancelled) setLoadingStatus(false);
      });

    return () => {
      cancelled = true;
    };
  }, [airport.code]);

  const handleStatusChange = async () => {
    const nextActive = !active;
    setActive(nextActive);
    setSavingStatus(true);
    setStatusError("");

    try {
      const response = await updateAirportStatus(airport.code, nextActive);
      setActive(response.active);
    } catch {
      setActive(!nextActive);
      setStatusError("No se pudo actualizar en BD.");
    } finally {
      setSavingStatus(false);
    }
  };

  return (
    <>
      <div className="airport-status-control">
        <div>
          <span>Estado operativo</span>
          <strong>{active ? "Activo" : "Inactivo"}</strong>
        </div>
        <button
          type="button"
          role="switch"
          aria-checked={active}
          className={`switch ${active ? "on" : ""}`}
          onClick={handleStatusChange}
          disabled={loadingStatus || savingStatus}
          title={active ? "Desactivar aeropuerto" : "Activar aeropuerto"}
        >
          <span />
        </button>
      </div>
      {statusError && <div className="inline-error">{statusError}</div>}

      <div className="metrics">
        <Metric label="Carga actual" value={load} sub={`cap. ${airport.maxCapacity}`} />
        <Metric
          label="Uso actual"
          value={`${Math.round(utilization * 100)}%`}
          sub={status.toUpperCase()}
        />
        <Metric
          label={peakLabel}
          value={airport.peakLoad}
          sub={`${Math.round(airport.utilization * 100)}%`}
        />
        <Metric label="Ubicación" value={airport.country} sub={airport.continent} />
      </div>
    </>
  );
}
