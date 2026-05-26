export function compactDate(date: string): string {
  return date.replaceAll("-", "");
}

export function formatClock(date: Date): string {
  return date.toLocaleTimeString("es-PE", {
    hour: "2-digit",
    minute: "2-digit",
    second: "2-digit",
  });
}

export function formatDateOnly(value: string | Date | undefined): string {
  if (!value) return "--";
  return new Date(value).toLocaleDateString("es-PE", {
    year: "numeric",
    month: "2-digit",
    day: "2-digit",
  });
}

export function formatTimeOnly(value: string | Date | undefined): string {
  if (!value) return "--";
  return new Date(value).toLocaleTimeString("es-PE", {
    hour: "2-digit",
    minute: "2-digit",
  });
}

export function formatSimMinute(value: number): string {
  const minute = Math.max(0, Math.floor(value));
  const day = Math.floor(minute / 1440);
  const dayMinute = minute % 1440;
  const hour = Math.floor(dayMinute / 60);
  const min = dayMinute % 60;
  return `Dia ${day} · ${String(hour).padStart(2, "0")}:${String(min).padStart(2, "0")}`;
}

export function formatFlightMoment(data: any, absoluteMinute: number): string {
  if (!data?.simulationStartDateTime && data?.simulationStartDate)
    return formatSimMinute(absoluteMinute);
  if (!data?.simulationStartDateTime) return formatSimMinute(absoluteMinute);
  const date = new Date(
    new Date(data.simulationStartDateTime).getTime() + absoluteMinute * 60000
  );
  return `${formatDateOnly(date)}, ${formatTimeOnly(date)}`;
}

export function hhmm(value: number): string {
  const minute = ((Math.floor(value) % 1440) + 1440) % 1440;
  return `${String(Math.floor(minute / 60)).padStart(2, "0")}:${String(minute % 60).padStart(2, "0")}`;
}

export function percent(part: number, total: number): number {
  return total ? Math.round((part / total) * 100) : 0;
}
