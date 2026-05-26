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
}

export function AirportDetail({ airport, load }: AirportDetailProps) {
  const utilization = airport.maxCapacity ? load / airport.maxCapacity : 0;
  const status = capacityStatus(utilization);

  return (
    <div className="metrics">
      <Metric label="Carga actual" value={load} sub={`cap. ${airport.maxCapacity}`} />
      <Metric
        label="Uso actual"
        value={`${Math.round(utilization * 100)}%`}
        sub={status.toUpperCase()}
      />
      <Metric
        label="Pico ALNS"
        value={airport.peakLoad}
        sub={`${Math.round(airport.utilization * 100)}%`}
      />
      <Metric label="Ubicacion" value={airport.country} sub={airport.continent} />
    </div>
  );
}
