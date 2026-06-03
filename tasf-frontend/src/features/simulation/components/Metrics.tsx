import type { SimulationData } from "../types";
import { percent } from "../utils/formatters";

interface MetricsProps {
  data: SimulationData;
}

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

export function Metrics({ data }: MetricsProps) {
  const metrics = data.metrics;
  const plannedPct = percent(metrics.plannedShipments, metrics.shipments);
  const onTimePct = percent(metrics.onTimeShipments, metrics.shipments);

  return (
    <div className="metrics">
      <Metric
        label="Envíos planificados"
        value={`${metrics.plannedShipments}/${metrics.shipments}`}
        sub={`${plannedPct}%`}
      />
      <Metric
        label="A tiempo"
        value={`${metrics.onTimeShipments}`}
        sub={`${onTimePct}%`}
      />
      <Metric
        label="Maletas"
        value={metrics.plannedBags}
        sub={`de ${metrics.totalBags}`}
      />
      <Metric
        label="Vuelos usados"
        value={metrics.usedFlights}
        sub={`${metrics.iterations} iter.`}
      />
      <Metric
        label="Fitness final"
        value={Math.round(metrics.fitnessFinal)}
        sub={`ini ${Math.round(metrics.fitnessInitial)}`}
      />
      <Metric
        label="Aceptadas SA"
        value={metrics.acceptedBySa}
        sub={`${metrics.globalImprovements} mejoras`}
      />
    </div>
  );
}
