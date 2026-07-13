import type { SimulationData } from "../../types";
import { formatDateOnly, formatFlightMoment, formatTimeOnly } from "../../utils/formatters";
import { formatRealTime } from "../../utils/timeUtils";
import { DraggableMapOverlay } from "./DraggableMapOverlay";

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

interface StackedStatusCardProps {
  items: Array<{
    label: string;
    value: string;
  }>;
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

function StackedStatusCard({ items }: StackedStatusCardProps) {
  return (
    <div className="map-stacked-status-card">
      {items.map((item) => (
        <div className="map-stacked-status-row" key={item.label}>
          <span>{item.label}</span>
          <strong>{item.value}</strong>
        </div>
      ))}
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
  subtitle = "Simulación 5 días",
  displayGmtOffset,
  displayAirportLabel,
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
          label="Fecha y hora de inicio"
          value={formatDateTime(data?.simulationStartDateTime, displayGmtOffset)}
          sub={displayAirportLabel || (data ? "inicio programado" : "--")}
        />
        <StatusItem
          label="Fecha y hora en simulación"
          value={data ? formatFlightMoment(data, simMinute, displayGmtOffset) : "--"}
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

export function SimulationStatusCards({
  data,
  simMinute,
  durationMs,
  displayGmtOffset,
}: Pick<TopbarProps, "data" | "simMinute" | "durationMs" | "displayGmtOffset" | "displayAirportLabel">) {
  const minutesFromStart = data ? simMinute - (data.startOffsetMinutes ?? 0) : 0;
  const simulationDurationMs = durationMs ?? data?.runtimeMs ?? 0;

  return (
    <>
      <DraggableMapOverlay initialX={18} initialY={18} className="map-status-overlay">
        <StackedStatusCard
          items={[
            {
              label: "Fecha y hora de inicio",
              value: formatDateTime(data?.simulationStartDateTime, displayGmtOffset),
            },
            {
              label: "Fecha y hora en simulación",
              value: data ? formatFlightMoment(data, simMinute, displayGmtOffset) : "--",
            },
          ]}
        />
      </DraggableMapOverlay>
      <DraggableMapOverlay initialX={18} initialY={92} className="map-status-overlay">
        <StackedStatusCard
          items={[
            {
              label: "Duración de la simulación",
              value: data ? formatRealTime(simulationDurationMs) : "--",
            },
            {
              label: "Tiempo transcurrido en simulación",
              value: data ? formatElapsedSimulation(minutesFromStart) : "--",
            },
          ]}
        />
      </DraggableMapOverlay>
    </>
  );
}
