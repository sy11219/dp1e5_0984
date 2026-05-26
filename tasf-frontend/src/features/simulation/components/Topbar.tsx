import type { SimulationData } from "../types";
import { formatClock, formatDateOnly, formatTimeOnly, formatSimMinute } from "../utils/formatters";

interface TopbarProps {
  data: SimulationData | null;
  now: Date;
  simMinute: number;
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

export function Topbar({ data, now, simMinute }: TopbarProps) {
  return (
    <header className="topbar">
      <div className="brand">
        <strong>TASF.B2B · Simulador de equipaje</strong>
        <span>Escenario operativo ALNS</span>
      </div>
      <div className="status-strip">
        <StatusItem label="Ahora" value={formatClock(now)} sub={formatDateOnly(now)} />
        <StatusItem
          label="Reloj simulado"
          value={formatSimMinute(simMinute)}
          sub="avance actual"
        />
        <StatusItem
          label="ALNS inicio"
          value={data ? formatTimeOnly(data.realStartedAt) : "--"}
          sub={data ? formatDateOnly(data.realStartedAt) : "--"}
        />
        <StatusItem
          label="ALNS fin"
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
          label="Duracion ALNS"
          value={data ? `${(data.runtimeMs / 1000).toFixed(2)} s` : "--"}
          sub="ejecucion real"
        />
        <StatusItem
          label="Escenario"
          value={data?.scenario || "ALNS"}
          sub="planificacion"
        />
      </div>
    </header>
  );
}
