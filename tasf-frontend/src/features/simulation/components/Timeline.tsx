import type { SimulationData } from "../types";
import { formatSimMinute } from "../utils/formatters";

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

  const baseDate = new Date(startDate);

  const currentDay = Math.floor(simMinute / 1440);
  const currentDate = new Date(baseDate.getTime() + currentDay * 24 * 60 * 60 * 1000);

  const lastDay = Math.floor(maxMinute / 1440);
  const lastDate = new Date(baseDate.getTime() + lastDay * 24 * 60 * 60 * 1000);

  const formatDate = (date: Date) =>
    date.toLocaleDateString("es-ES", { day: "2-digit", month: "2-digit", year: "numeric" });

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
        <span>{`Día 0 · ${formatDate(baseDate)} · 00:00`}</span>
        <strong>{`Día ${currentDay} · ${formatDate(currentDate)} · ${formatSimMinute(simMinute)}`}</strong>
        <span>{`Día ${lastDay} · ${formatDate(lastDate)} · 00:00`}</span>
      </div>
    </div>
  );
}
