export type StoredMapFocus = {
  type: "airport" | "flight";
  id: string;
};

export function readMapFocus(key: string): StoredMapFocus | null {
  if (typeof window === "undefined") return null;

  try {
    const raw = window.localStorage.getItem(key);
    if (!raw) return null;

    const value = JSON.parse(raw) as Partial<StoredMapFocus>;
    if ((value.type === "airport" || value.type === "flight") && typeof value.id === "string") {
      return { type: value.type, id: value.id };
    }
  } catch {
    // Local storage can be unavailable or contain stale data.
  }

  return null;
}

export function writeMapFocus(key: string, focus: StoredMapFocus | null) {
  if (typeof window === "undefined") return;

  try {
    if (focus) {
      window.localStorage.setItem(key, JSON.stringify(focus));
    } else {
      window.localStorage.removeItem(key);
    }
  } catch {
    // Ignore storage failures; selection still works for the current screen.
  }
}
