export type CapacityThresholds = {
  gray: number;
  green: number;
  yellow: number;
  red: number;
};

export const DEFAULT_CAPACITY_THRESHOLDS: CapacityThresholds = {
  gray: 0,
  green: 30,
  yellow: 60,
  red: 100,
};

const CAPACITY_THRESHOLDS_STORAGE_KEY = "tasf.capacityThresholds.v1";

function clampThreshold(value: number): number {
  if (!Number.isFinite(value)) return 0;
  return Math.min(100, Math.max(0, Math.round(value)));
}

export function normalizeCapacityThresholds(
  thresholds?: Partial<CapacityThresholds> | null
): CapacityThresholds {
  const base = thresholds ?? {};
  const gray = clampThreshold(base.gray ?? DEFAULT_CAPACITY_THRESHOLDS.gray);
  const green = clampThreshold(base.green ?? DEFAULT_CAPACITY_THRESHOLDS.green);
  const yellow = clampThreshold(base.yellow ?? DEFAULT_CAPACITY_THRESHOLDS.yellow);
  const red = clampThreshold(base.red ?? DEFAULT_CAPACITY_THRESHOLDS.red);

  const safeGreen = Math.max(green, gray);
  const safeYellow = Math.max(yellow, safeGreen);
  const safeRed = Math.max(red, safeYellow);

  return {
    gray: Math.min(gray, safeGreen),
    green: safeGreen,
    yellow: safeYellow,
    red: safeRed,
  };
}

export function getCapacityThresholds(): CapacityThresholds {
  try {
    const stored = window.localStorage.getItem(CAPACITY_THRESHOLDS_STORAGE_KEY);
    if (!stored) return { ...DEFAULT_CAPACITY_THRESHOLDS };

    const parsed = JSON.parse(stored) as Partial<CapacityThresholds> | null;
    return normalizeCapacityThresholds(parsed);
  } catch {
    return { ...DEFAULT_CAPACITY_THRESHOLDS };
  }
}

export function setCapacityThresholds(thresholds: Partial<CapacityThresholds>): CapacityThresholds {
  const normalized = normalizeCapacityThresholds(thresholds);

  try {
    window.localStorage.setItem(
      CAPACITY_THRESHOLDS_STORAGE_KEY,
      JSON.stringify(normalized)
    );
  } catch {
    // Ignore local storage failures.
  }

  return normalized;
}

export function resetCapacityThresholds(): CapacityThresholds {
  return setCapacityThresholds(DEFAULT_CAPACITY_THRESHOLDS);
}
