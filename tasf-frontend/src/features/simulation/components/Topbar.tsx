import type { SimulationData } from "../types";
import { formatDateOnly, formatFlightMoment, formatTimeOnly } from "../utils/formatters";
import { formatRealTime } from "../utils/timeUtils";

interface TopbarProps {
  data: SimulationData | null;
  simMinute: number;
  durationMs?: number;
  title?: string;
  subtitle?: string;
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

function formatDateTime(value: string | Date | undefined): string {
  if (!value) return "--";
  return `${formatDateOnly(value)} ${formatTimeOnly(value)}`;
}

function formatElapsedSimulation(minutes: number): string {
  const totalSeconds = Math.max(0, Math.floor(minutes * 60));
  const hours = Math.floor(totalSeconds / 3600);
  const mins = Math.floor((totalSeconds % 3600) / 60);
  const secs = totalSeconds % 60;
  return `${String(hours).padStart(2, "0")}:${String(mins).padStart(2, "0")}:${String(secs).padStart(2, "0")}`;
}

export function Topbar({
  data,
  simMinute,
  durationMs,
  title = "TASF.B2B - Simulador de equipaje",
  subtitle = "Simulación 5 días",
}: TopbarProps) {
  const minutesFromStart = data ? simMinute - (data.startOffsetMinutes ?? 0) : 0;
  const simulationDurationMs = durationMs ?? data?.runtimeMs ?? 0;
  const simulatedDayLabel = data
    ? `Día ${Math.floor(Math.max(0, minutesFromStart) / 1440) + 1}`
    : "--";

  return (
    <header className="topbar">
      <div className="brand">
        <strong>{title}</strong>
        <span>{subtitle}</span>
      </div>
      <div className="status-strip">
        <StatusItem
          label="Fecha y Hora de Inicio"
          value={formatDateTime(data?.simulationStartDateTime)}
          sub={data ? "inicio programado" : "--"}
        />
        <StatusItem
          label="Fecha y Hora en Simulación"
          value={data ? formatFlightMoment(data, simMinute) : "--"}
          sub={simulatedDayLabel}
        />
        <StatusItem
          label="Duración de la simulación"
          value={data ? formatRealTime(simulationDurationMs) : "--"}
          sub="ejecución real"
        />
        <StatusItem
          label="Tiempo transcurrido en simulación"
          value={data ? formatElapsedSimulation(minutesFromStart) : "--"}
          sub="avance acumulado"
        />
      </div>
    </header>
  );
}
