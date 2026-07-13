const OPERATIONAL_STATUS_LABELS: Record<string, string> = {
  ACTIVE: "Activo",
  INACTIVE: "Inactivo",
};

const FLIGHT_SCHEDULE_STATUS_LABELS: Record<string, string> = {
  SCHEDULED: "Programado",
  CANCELED: "Cancelado",
  CANCELLED: "Cancelado",
};

export function operationalStatusLabel(status?: string, active?: boolean): string {
  const value = normalizeStatus(status || (active === false ? "INACTIVE" : "ACTIVE"));
  return OPERATIONAL_STATUS_LABELS[value] ?? formatUnknownStatus(value);
}

export function flightScheduleStatusLabel(status?: string): string {
  const value = normalizeStatus(status || "SCHEDULED");
  return FLIGHT_SCHEDULE_STATUS_LABELS[value] ?? formatUnknownStatus(value);
}

function normalizeStatus(value: string): string {
  return value.trim().toUpperCase();
}

function formatUnknownStatus(value: string): string {
  return value
    .toLowerCase()
    .split(/[_\s-]+/)
    .filter(Boolean)
    .map((part) => part.charAt(0).toUpperCase() + part.slice(1))
    .join(" ");
}
