import type { SimulationData } from "../../types";
import { formatDateOnly, formatFlightMoment, formatTimeOnly } from "../../utils/formatters";
import { formatRealTime } from "../../utils/timeUtils";

interface TopbarProps {
  data: SimulationData | null;
  simMinute: number;
  durationMs?: number;
  title?: string;
  subtitle?: string;
  displayGmtOffset?: number;
  displayAirportLabel?: string;
}

interface StatusItemProps {
  label: string;
  value: string;
  sub?: string;
}

export function StatusItem({ label, value, sub }: StatusItemProps) {
  return (
    <div className="status-item">
      <span>{label}</span>
      <strong>{value}</strong>
      {sub && <small>{sub}</small>}
    </div>
  );
}

function formatDateTime(value: string | Date | undefined, gmtOffset?: number): string {
  if (!value) return "--";
  return `${formatDateOnly(value, gmtOffset)} ${formatTimeOnly(value, gmtOffset)}`;
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
  subtitle = "Simulacion 5 dias",
  displayGmtOffset,
  displayAirportLabel,
}: TopbarProps) {
  const minutesFromStart = data ? simMinute - (data.startOffsetMinutes ?? 0) : 0;
  const simulationDurationMs = durationMs ?? data?.runtimeMs ?? 0;
  const simulatedDayLabel = data
    ? `Dia ${Math.floor(Math.max(0, minutesFromStart) / 1440) + 1}`
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
          value={formatDateTime(data?.simulationStartDateTime, displayGmtOffset)}
          sub={displayAirportLabel || (data ? "inicio programado" : "--")}
        />
        <StatusItem
          label="Fecha y Hora en Simulacion"
          value={data ? formatFlightMoment(data, simMinute, displayGmtOffset) : "--"}
          sub={simulatedDayLabel}
        />
        <StatusItem
          label="Duracion de la simulacion"
          value={data ? formatRealTime(simulationDurationMs) : "--"}
          sub="ejecucion real"
        />
        <StatusItem
          label="Tiempo transcurrido en simulacion"
          value={data ? formatElapsedSimulation(minutesFromStart) : "--"}
          sub="avance acumulado"
        />
      </div>
    </header>
  );
}

export function SimulationStatusCards({
  data,
  simMinute,
  durationMs,
  displayGmtOffset,
  displayAirportLabel,
}: Pick<TopbarProps, "data" | "simMinute" | "durationMs" | "displayGmtOffset" | "displayAirportLabel">) {
  const minutesFromStart = data ? simMinute - (data.startOffsetMinutes ?? 0) : 0;
  const simulationDurationMs = durationMs ?? data?.runtimeMs ?? 0;
  const simulatedDayLabel = data
    ? `Dia ${Math.floor(Math.max(0, minutesFromStart) / 1440) + 1}`
    : "--";

  return (
    <>
      <div className="status-strip map-status-strip map-status-strip-top-left">
        <StatusItem
          label="Fecha y Hora de Inicio"
          value={formatDateTime(data?.simulationStartDateTime, displayGmtOffset)}
          sub={displayAirportLabel || (data ? "inicio programado" : "--")}
        />
        <StatusItem
          label="Fecha y Hora en Simulacion"
          value={data ? formatFlightMoment(data, simMinute, displayGmtOffset) : "--"}
          sub={simulatedDayLabel}
        />
      </div>
      <div className="status-strip map-status-strip map-status-strip-bottom-right">
        <StatusItem
          label="Duracion de la simulacion"
          value={data ? formatRealTime(simulationDurationMs) : "--"}
          sub="ejecucion real"
        />
        <StatusItem
          label="Tiempo transcurrido en simulacion"
          value={data ? formatElapsedSimulation(minutesFromStart) : "--"}
          sub="avance acumulado"
        />
      </div>
    </>
  );
}
