import type { SimulationData } from "../types";
import { formatClock, formatDateOnly, formatFlightMoment, formatSimMinute, formatTimeOnly } from "../utils/formatters";

interface TopbarProps {
  data: SimulationData | null;
  now: Date;
  simMinute: number;
  title?: string;
  subtitle?: string;
  clockLabel?: string;
}

interface StatusItemProps {
  label: string;
  value: string;
  sub?: string;
}

function StatusItem({ label, value, sub }: StatusItemProps) {
  return (
    <div className="status-item">
      <span>{label}</span>
      <strong>{value}</strong>
      {sub && <small>{sub}</small>}
    </div>
  );
}

export function Topbar({
  data,
  now,
  simMinute,
  title = "TASF.B2B - Simulador de equipaje",
  subtitle = "Simulación 5 días",
  clockLabel = "Reloj simulado",
}: TopbarProps) {
  return (
    <header className="topbar">
      <div className="brand">
        <strong>{title}</strong>
        <span>{subtitle}</span>
      </div>
      <div className="status-strip">
        <StatusItem label="Ahora" value={formatClock(now)} sub={formatDateOnly(now)} />
        <StatusItem
          label={clockLabel}
          value={formatSimMinute(simMinute)}
          sub={data ? formatFlightMoment(data, simMinute) : "avance actual"}
        />
        <StatusItem
          label="Inicio"
          value={data ? formatTimeOnly(data.realStartedAt) : "--"}
          sub={data ? formatDateOnly(data.realStartedAt) : "--"}
        />
        <StatusItem
          label="Ultima actualización"
          value={data ? formatTimeOnly(data.realFinishedAt) : "--"}
          sub={data ? formatDateOnly(data.realFinishedAt) : "--"}
        />
        <StatusItem
          label="Simulado desde"
          value={data ? formatDateOnly(data.simulationStartDateTime) : "--"}
          sub={data ? formatTimeOnly(data.simulationStartDateTime) : "--"}
        />
        <StatusItem
          label="Simulado hasta"
          value={data ? formatDateOnly(data.simulationEndDateTime) : "--"}
          sub={data ? formatTimeOnly(data.simulationEndDateTime) : "--"}
        />
        <StatusItem
          label="Duración"
          value={data ? `${(data.runtimeMs / 1000).toFixed(2)} s` : "--"}
          sub="ejecución real"
        />
        <StatusItem
          label="Escenario"
          value={data?.scenario || "Simulación 5 días"}
          sub="--"
        />
      </div>
    </header>
  );
}
