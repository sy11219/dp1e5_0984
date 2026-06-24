export const STATUS_COLOR = {
  green: "#21a67a",
  yellow: "#d9a219",
  red: "#d84545",
} as const;

function currentLocalDateTime() {
  const now = new Date();
  const local = new Date(now.getTime() - now.getTimezoneOffset() * 60_000);
  return local.toISOString().slice(0, 16);
}

const defaultLocalDateTime = currentLocalDateTime();

export const SIMULATION_DAYS = 5;
export const DEFAULT_START_DATE = defaultLocalDateTime.slice(0, 10);
export const DEFAULT_START_TIME = defaultLocalDateTime.slice(11, 16);
export const DEFAULT_SPEED = 360;
export const SPEED_MIN = 60;
export const SPEED_MAX = 1800;
export const SPEED_STEP = 60;

export const MAP_CONFIG = {
  center: [10, -5] as const,
  zoom: 2,
  worldCopyJump: true,
  zoomControl: true,
  preferCanvas: true,
  maxBounds: [[-58, -115], [62, 95]] as const,
  maxBoundsViscosity: 0.4,
} as const;

export const PANE_Z_INDEX = {
  routes: "430",
  activeFlights: "620",
  airports: "660",
} as const;
