import { useMemo } from "react";
import type { SimulationData } from "../types";
import { capacityStatus, computeAirportLoads } from "../utils/calculations";

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
  samplingIntervalMinutes,
}: GlobalIndicatorsProps) {
  const minute = sampledMinute(currentMinute, samplingIntervalMinutes);
  const {
    airportBags,
    airportCapacity,
    assignedBags,
    fleetCapacity,
  } = useMemo(() => {
    if (!data) {
      return {
        airportBags: 0,
        airportCapacity: 0,
        assignedBags: 0,
        fleetCapacity: 0,
      };
    }

    const airportLoads = computeAirportLoads(data, minute);
    const airportBags = data.airports.reduce((sum, airport) => {
      const load = Math.max(0, airportLoads[airport.code] || 0);
      return sum + load;
    }, 0);
    const airportCapacity = data.airports.reduce(
      (sum, airport) => sum + Math.max(0, airport.maxCapacity || 0),
      0
    );

    const assignedBags = data.flights.reduce((sum, flight) => {
      const load = Math.max(0, flight.assignedLoad || 0);
      return sum + load;
    }, 0);
    const fleetCapacity = data.flights.reduce(
      (sum, flight) => sum + Math.max(0, flight.maxCapacity || 0),
      0
    );

    return {
      airportBags,
      airportCapacity,
      assignedBags,
      fleetCapacity,
    };
  }, [data, minute]);

  if (!data) {
    return (
      <section className="panel section">
        <h3>Indicadores globales</h3>
        <div className="empty-state">Esperando datos para calcular ocupación.</div>
      </section>
    );
  }

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
