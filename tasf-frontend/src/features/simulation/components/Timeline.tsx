import type { SimulationData } from "../types";
import { formatFlightMoment, formatSimMinute } from "../utils/formatters";

interface TimelineProps {
  simMinute: number;
  maxMinute: number;
  setSimMinute: (minute: number) => void;
  data: SimulationData | null;
  startDate: string;
  displayGmtOffset?: number;
}

export function Timeline({
  simMinute,
  maxMinute,
  setSimMinute,
  data,
  startDate,
  displayGmtOffset,
}: TimelineProps) {
  const handleChange = (event: React.ChangeEvent<HTMLInputElement>) => {
    setSimMinute(Number(event.target.value));
  };

  const startOffset = data?.startOffsetMinutes ?? 0;
  const startLabel = data
    ? formatFlightMoment(data, startOffset, displayGmtOffset)
    : `${startDate} ${formatSimMinute(startOffset, displayGmtOffset ?? 0)}`;
  const currentLabel = data
    ? formatFlightMoment(data, simMinute, displayGmtOffset)
    : `${startDate} ${formatSimMinute(startOffset, displayGmtOffset ?? 0)}`;
  const endLabel = data
    ? formatFlightMoment(data, maxMinute, displayGmtOffset)
    : `${new Date(new Date(startDate).getTime() + 5 * 24 * 60 * 60 * 1000)
          .toISOString()
          .slice(0, 10)} ${formatSimMinute(startOffset, displayGmtOffset ?? 0)}`;

  return (
    <div className="timeline">
      <input
        type="range"
        min={startOffset}
        max={maxMinute}
        value={Math.floor(simMinute)}
        onChange={handleChange}
        disabled={!data}
      />
      <div className="timeline-meta">
        <span>{startLabel}</span>
        <strong>{currentLabel}</strong>
        <span>{endLabel}</span>
      </div>
    </div>
  );
}
