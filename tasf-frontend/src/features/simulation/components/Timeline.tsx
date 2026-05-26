import type { SimulationData } from "../types";
import { formatSimMinute } from "../utils/formatters";

interface TimelineProps {
  simMinute: number;
  maxMinute: number;
  setSimMinute: (minute: number) => void;
  data: SimulationData | null;
}

export function Timeline({ simMinute, maxMinute, setSimMinute, data }: TimelineProps) {
  const handleChange = (event: React.ChangeEvent<HTMLInputElement>) => {
    setSimMinute(Number(event.target.value));
  };

  return (
    <div className="timeline">
      <input
        type="range"
        min="0"
        max={maxMinute}
        value={Math.floor(simMinute)}
        onChange={handleChange}
        disabled={!data}
      />
      <div className="timeline-meta">
        <span>Dia 0 · 00:00</span>
        <strong>{formatSimMinute(simMinute)}</strong>
        <span>{`Dia ${Math.floor(maxMinute / 1440)} · 00:00`}</span>
      </div>
    </div>
  );
}
