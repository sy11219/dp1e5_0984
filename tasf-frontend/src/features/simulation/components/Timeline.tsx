import type { SimulationData } from "../types";
import { formatFlightMoment, formatSimMinute } from "../utils/formatters";

interface TimelineProps {
  simMinute: number;
  maxMinute: number;
  setSimMinute: (minute: number) => void;
  data: SimulationData | null;
  startDate: string;
}

export function Timeline({ simMinute, maxMinute, setSimMinute, data, startDate }: TimelineProps) {
  const handleChange = (event: React.ChangeEvent<HTMLInputElement>) => {
    setSimMinute(Number(event.target.value));
  };

  const startOffset = data?.startOffsetMinutes ?? 0;
  const startLabel = data
    ? formatFlightMoment(data, startOffset)
    : `${startDate} ${formatSimMinute(startOffset)}`;
  const currentLabel = data
    ? formatFlightMoment(data, simMinute)
    : `${startDate} ${formatSimMinute(startOffset)}`;
  const endLabel = data
    ? formatFlightMoment(data, maxMinute)
    : `${new Date(new Date(startDate).getTime() + 5 * 24 * 60 * 60 * 1000)
          .toISOString()
          .slice(0, 10)} ${formatSimMinute(startOffset)}`;

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
