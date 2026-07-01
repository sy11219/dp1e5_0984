import type { SimulationData } from "../types";

type FlightMomentData =
  | (Pick<SimulationData, "simulationStartDateTime" | "startOffsetMinutes"> & {
      simulationStartDate?: string;
    })
  | null
  | undefined;

export function compactDate(date: string): string {
  return date.replaceAll("-", "");
}

function pad(value: number): string {
  return String(value).padStart(2, "0");
}

function datePartsWithOffset(value: string | Date, gmtOffset?: number) {
  const date = new Date(value);
  if (typeof gmtOffset !== "number") {
    return {
      year: date.getFullYear(),
      month: date.getMonth() + 1,
      day: date.getDate(),
      hour: date.getHours(),
      minute: date.getMinutes(),
      second: date.getSeconds(),
    };
  }

  const shifted = new Date(date.getTime() + gmtOffset * 60 * 60 * 1000);
  return {
    year: shifted.getUTCFullYear(),
    month: shifted.getUTCMonth() + 1,
    day: shifted.getUTCDate(),
    hour: shifted.getUTCHours(),
    minute: shifted.getUTCMinutes(),
    second: shifted.getUTCSeconds(),
  };
}

export function formatClock(date: Date, gmtOffset?: number): string {
  if (typeof gmtOffset !== "number") {
    return date.toLocaleTimeString("es-PE", {
      hour: "2-digit",
      minute: "2-digit",
      second: "2-digit",
    });
  }

  const parts = datePartsWithOffset(date, gmtOffset);
  return `${pad(parts.hour)}:${pad(parts.minute)}:${pad(parts.second)}`;
}

export function formatDateOnly(value: string | Date | undefined, gmtOffset?: number): string {
  if (!value) return "--";
  if (typeof gmtOffset === "number") {
    const parts = datePartsWithOffset(value, gmtOffset);
    return `${pad(parts.day)}/${pad(parts.month)}/${parts.year}`;
  }
  return new Date(value).toLocaleDateString("es-PE", {
    year: "numeric",
    month: "2-digit",
    day: "2-digit",
  });
}

export function formatTimeOnly(value: string | Date | undefined, gmtOffset?: number): string {
  if (!value) return "--";
  if (typeof gmtOffset === "number") {
    const parts = datePartsWithOffset(value, gmtOffset);
    return `${pad(parts.hour)}:${pad(parts.minute)}`;
  }
  return new Date(value).toLocaleTimeString("es-PE", {
    hour: "2-digit",
    minute: "2-digit",
  });
}

export function formatSimMinute(value: number, gmtOffset = 0): string {
  const minute = ((Math.floor(value + gmtOffset * 60) % 1440) + 1440) % 1440;
  const dayMinute = minute % 1440;
  const hour = Math.floor(dayMinute / 60);
  const min = dayMinute % 60;
  return `${pad(hour)}:${pad(min)}`;
}

export function formatFlightMoment(
  data: FlightMomentData,
  absoluteMinute: number,
  gmtOffset?: number
): string {
  if (!data?.simulationStartDateTime && data?.simulationStartDate)
    return formatSimMinute(absoluteMinute, gmtOffset ?? 0);
  if (!data?.simulationStartDateTime) return formatSimMinute(absoluteMinute, gmtOffset ?? 0);
  const minutesFromStart = absoluteMinute - (data.startOffsetMinutes ?? 0);
  const date = new Date(
    new Date(data.simulationStartDateTime).getTime() + minutesFromStart * 60000
  );
  return `${formatDateOnly(date, gmtOffset)}, ${formatTimeOnly(date, gmtOffset)}`;
}

export function hhmm(value: number, gmtOffset = 0): string {
  const minute = ((Math.floor(value + gmtOffset * 60) % 1440) + 1440) % 1440;
  return `${pad(Math.floor(minute / 60))}:${pad(minute % 60)}`;
}

export function percent(part: number, total: number): number {
  return total ? Math.round((part / total) * 100) : 0;
}
