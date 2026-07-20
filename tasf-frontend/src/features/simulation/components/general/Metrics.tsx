import type { LucideIcon } from "lucide-react";
import { BriefcaseBusiness, Clock3, PackageCheck, Plane, Truck } from "lucide-react";
import type { SimulationData } from "../../types";
import { percent } from "../../utils/formatters";

interface MetricsProps {
  data: SimulationData;
}

interface MetricProps {
  label: string;
  value: string | number;
  sub: string | number;
  icon: LucideIcon;
}

function Metric({ label, value, sub, icon: Icon }: MetricProps) {
  return (
    <div className="metric">
      <span className="metric-label">{label}</span>
      <div className="metric-value-row">
        <strong>{value}</strong>
        <div className="metric-icon">
          <Icon size={18} />
        </div>
      </div>
      <span>{sub}</span>
    </div>
  );
}

export function Metrics({ data }: MetricsProps) {
  const metrics = data.metrics;
  const plannedPct = percent(metrics.plannedShipments, metrics.shipments);
  const onTimePct = percent(metrics.onTimeShipments, metrics.shipments);
  const deliveredShipments = metrics.deliveredShipments ?? metrics.onTimeShipments;
  const deliveredPct = percent(deliveredShipments, metrics.shipments);

  return (
    <div className="metrics">
      <Metric
        label="Envíos planificados"
        value={`${metrics.plannedShipments}/${metrics.shipments}`}
        sub={`${plannedPct}%`}
        icon={PackageCheck}
      />
      <Metric
        label="Entregados 120h"
        value={`${deliveredShipments}/${metrics.shipments}`}
        sub={`${deliveredPct}%`}
        icon={Truck}
      />
      <Metric
        label="A tiempo"
        value={`${metrics.onTimeShipments}`}
        sub={`${onTimePct}%`}
        icon={Clock3}
      />
      <Metric
        label="Maletas"
        value={metrics.plannedBags}
        sub={`de ${metrics.totalBags}`}
        icon={BriefcaseBusiness}
      />
      <Metric
        label="Vuelos usados"
        value={metrics.usedFlights}
        sub={`${metrics.iterations} iter.`}
        icon={Plane}
      />
    </div>
  );
}
