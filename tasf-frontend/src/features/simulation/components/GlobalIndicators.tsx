import type { Flight, SimulationData } from "../types";
import { capacityStatus, computeAirportLoads } from "../utils/calculations";
import { percent } from "../utils/formatters";

type TrafficStatus = "green" | "yellow" | "red" | "gray";

interface GlobalIndicatorsProps {
  data: SimulationData | null;
  currentMinute: number;
  planningWindowMinutes?: number;
  fleetFlights?: Flight[];
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

function TrafficMetric({ label, numerator, denominator }: TrafficMetricProps) {
  const value = percent(numerator, denominator);
  const status = trafficStatus(value);

  return (
    <div className={`metric traffic-metric traffic-metric-${status}`}>
      <span>{label}</span>
      <strong>{`${value}%`}</strong>
      <span>{`${numerator.toLocaleString("es-PE")} / ${denominator.toLocaleString("es-PE")} maletas`}</span>
    </div>
  );
}

export function GlobalIndicators({
  data,
  currentMinute,
  planningWindowMinutes,
  fleetFlights,
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
  const airportBags = Object.values(airportLoads).reduce((sum, load) => sum + Math.max(0, load), 0);
  const airportCapacity = data.airports.reduce(
    (sum, airport) => sum + Math.max(0, airport.maxCapacity || 0),
    0
  );

  const fleetCapacitySource = fleetFlights?.length ? fleetFlights : data.flights;
  const assignedBags = data.flights.reduce(
    (sum, flight) => sum + Math.max(0, flight.assignedLoad || 0),
    0
  );
  const fleetCapacity = fleetCapacitySource.reduce(
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
