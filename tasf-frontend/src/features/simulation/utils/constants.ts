export const STATUS_COLOR = {
  green: "#21a67a",
  yellow: "#d9a219",
  red: "#d84545",
  gray: "#a8a7a7",
} as const;

export const SIMULATION_DAYS = 5;
export const DEFAULT_START_DATE = "2026-01-02";
export const DEFAULT_START_TIME = "00:00";
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
  activeFlights: "540",
  airports: "920",
} as const;
