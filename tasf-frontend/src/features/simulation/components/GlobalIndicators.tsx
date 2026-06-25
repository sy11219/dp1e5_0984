import type { SimulationData } from "../types";
import { capacityStatus, computeActiveFlights, computeAirportLoads } from "../utils/calculations";

type TrafficStatus = "green" | "yellow" | "red" | "gray";

interface GlobalIndicatorsProps {
  data: SimulationData | null;
  currentMinute: number;
  planningWindowMinutes?: number;
}

interface TrafficMetricProps {
  label: string;
  numerator: number;
  denominator: number;
}

function sampledMinute(currentMinute: number, planningWindowMinutes?: number): number {
  const interval =
    planningWindowMinutes && planningWindowMinutes > 0 && planningWindowMinutes <= 4
      ? planningWindowMinutes
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

function TrafficMetric({ label, numerator, denominator }: TrafficMetricProps) {
  const value = trafficPercent(numerator, denominator);
  const status = trafficStatus(value);

  return (
    <div className={`metric traffic-metric traffic-metric-${status}`}>
      <span>{label}</span>
      <strong>{`${formatTrafficPercent(value)}%`}</strong>
      <span>{`${numerator.toLocaleString("es-PE")} / ${denominator.toLocaleString("es-PE")} maletas`}</span>
    </div>
  );
}

export function GlobalIndicators({
  data,
  currentMinute,
  planningWindowMinutes,
}: GlobalIndicatorsProps) {
  if (!data) {
    return (
      <section className="panel section">
        <h3>Indicadores globales</h3>
        <div className="empty-state">Esperando datos para calcular ocupación.</div>
      </section>
    );
  }

  const minute = sampledMinute(currentMinute, planningWindowMinutes);
  const airportLoads = computeAirportLoads(data, minute);
  const airportBags = data.airports.reduce((sum, airport) => {
    const capacity = Math.max(0, airport.maxCapacity || 0);
    const load = Math.max(0, airportLoads[airport.code] || 0);
    return sum + Math.min(load, capacity);
  }, 0);
  const airportCapacity = data.airports.reduce(
    (sum, airport) => sum + Math.max(0, airport.maxCapacity || 0),
    0
  );

  const activeFlights = computeActiveFlights(data, minute);
  const assignedBags = activeFlights.reduce((sum, flight) => {
    const capacity = Math.max(0, flight.maxCapacity || 0);
    const load = Math.max(0, flight.assignedLoad || 0);
    return sum + Math.min(load, capacity);
  }, 0);
  const fleetCapacity = activeFlights.reduce(
    (sum, flight) => sum + Math.max(0, flight.maxCapacity || 0),
    0
  );

  return (
    <section className="panel section">
      <h3>Indicadores globales</h3>
      <div className="metrics global-indicators">
        <TrafficMetric
          label="Ocupación de la flota"
          numerator={assignedBags}
          denominator={fleetCapacity}
        />
        <TrafficMetric
          label="Ocupación de aeropuertos"
          numerator={airportBags}
          denominator={airportCapacity}
        />
      </div>
    </section>
  );
}
