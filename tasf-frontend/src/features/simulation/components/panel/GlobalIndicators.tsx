import type { LucideIcon } from "lucide-react";
import { Building2, Clock3, PackageCheck, Plane } from "lucide-react";
import { useMemo } from "react";
import type { SimulationData } from "../../types";
import { capacityStatus, computeAirportLoads } from "../../utils/calculations";

type TrafficStatus = "green" | "yellow" | "red" | "gray";

interface GlobalIndicatorsProps {
  data: SimulationData | null;
  currentMinute: number;
  samplingIntervalMinutes?: number;
}

interface TrafficMetricProps {
  label: string;
  numerator: number;
  denominator: number;
  icon: LucideIcon;
}

interface SummaryMetricProps {
  label: string;
  value: string;
  sub: string;
  icon: LucideIcon;
  status?: TrafficStatus;
}

function sampledMinute(currentMinute: number, samplingIntervalMinutes?: number): number {
  const interval =
    samplingIntervalMinutes && samplingIntervalMinutes > 0 && samplingIntervalMinutes <= 4
      ? samplingIntervalMinutes
      : 4;

  return Math.floor(Math.max(0, currentMinute) / interval) * interval;
}

function trafficStatus(value: number): TrafficStatus {
  return capacityStatus(value / 100);
}

function trafficPercent(numerator: number, denominator: number): number {
  return denominator ? (numerator / denominator) * 100 : 0;
}

function formatTrafficPercent(value: number): string {
  return value.toLocaleString("es-PE", {
    minimumFractionDigits: 2,
    maximumFractionDigits: 2,
  });
}

function TrafficMetric({ label, numerator, denominator, icon: Icon }: TrafficMetricProps) {
  const value = trafficPercent(numerator, denominator);
  const status = trafficStatus(value);

  return (
    <div className={`metric traffic-metric traffic-metric-${status}`}>
      <span className="metric-label">{label}</span>
      <div className="metric-value-row">
        <strong>{`${formatTrafficPercent(value)}%`}</strong>
        <div className="metric-icon">
          <Icon size={18} />
        </div>
      </div>
      <span>{`${numerator.toLocaleString("es-PE")} / ${denominator.toLocaleString("es-PE")} maletas`}</span>
    </div>
  );
}

function SummaryMetric({ label, value, sub, icon: Icon, status = "gray" }: SummaryMetricProps) {
  return (
    <div className={`metric traffic-metric traffic-metric-${status}`}>
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

export function GlobalIndicators({
  data,
  currentMinute,
  samplingIntervalMinutes,
}: GlobalIndicatorsProps) {
  const minute = sampledMinute(currentMinute, samplingIntervalMinutes);
  const airportBags = useMemo(() => {
    if (!data) return 0;

    const airportLoads = computeAirportLoads(data, minute);
    return data.airports.reduce((sum, airport) => {
      const load = Math.max(0, airportLoads[airport.code] || 0);
      return sum + load;
    }, 0);
  }, [data, minute]);

  const airportCapacity = useMemo(() => {
    if (!data) return 0;
    return data.airports.reduce(
      (sum, airport) => sum + Math.max(0, airport.maxCapacity || 0),
      0
    );
  }, [data]);

  const { assignedBags, fleetCapacity } = useMemo(() => {
    if (!data) {
      return {
        assignedBags: 0,
        fleetCapacity: 0,
      };
    }

    const assignedBags = data.flights.reduce((sum, flight) => {
      const load = Math.max(0, flight.assignedLoad || 0);
      return sum + load;
    }, 0);
    const fleetCapacity = data.flights.reduce(
      (sum, flight) => sum + Math.max(0, flight.maxCapacity || 0),
      0
    );

    return {
      assignedBags,
      fleetCapacity,
    };
  }, [data]);

  if (!data) {
    return (
      <section className="panel section">
        <h3>Indicadores globales</h3>
        <div className="empty-state">Esperando datos para calcular ocupación.</div>
      </section>
    );
  }

  const plannedPct = trafficPercent(data.metrics.plannedShipments, data.metrics.shipments);
  const onTimePct = trafficPercent(data.metrics.onTimeShipments, data.metrics.shipments);

  return (
    <section className="panel section">
      <h3>Indicadores globales</h3>
      <div className="metrics global-indicators">
        <TrafficMetric
          label="Flota"
          numerator={assignedBags}
          denominator={fleetCapacity}
          icon={Plane}
        />
        <TrafficMetric
          label="Aeropuertos"
          numerator={airportBags}
          denominator={airportCapacity}
          icon={Building2}
        />
        <SummaryMetric
          label="Envíos planificados"
          value={`${data.metrics.plannedShipments}/${data.metrics.shipments}`}
          sub={`${formatTrafficPercent(plannedPct)}%`}
          icon={PackageCheck}
          status={trafficStatus(plannedPct)}
        />
        <SummaryMetric
          label="Envíos a tiempo"
          value={`${data.metrics.onTimeShipments}/${data.metrics.shipments}`}
          sub={`${formatTrafficPercent(onTimePct)}%`}
          icon={Clock3}
          status={trafficStatus(onTimePct)}
        />
      </div>
    </section>
  );
}
