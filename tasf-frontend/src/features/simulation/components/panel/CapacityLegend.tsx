import { useEffect, useMemo, useState } from "react";
import {
  DEFAULT_CAPACITY_THRESHOLDS,
  getCapacityThresholds,
  normalizeCapacityThresholds,
  setCapacityThresholds,
} from "../../utils/capacityThresholds";

export function CapacityLegend() {
  const [thresholds, setThresholds] = useState(() => getCapacityThresholds());

  useEffect(() => {
    setThresholds(getCapacityThresholds());
  }, []);

  const handleChange = (key: keyof typeof thresholds, value: string) => {
    const nextValue = Number(value);
    const normalized = normalizeCapacityThresholds({
      ...thresholds,
      [key]: Number.isNaN(nextValue) ? 0 : nextValue,
    });
    setThresholds(normalized);
    setCapacityThresholds(normalized);
  };

  const summary = useMemo(() => {
    return [
      { color: "green", label: "Verde", value: thresholds.green },
      { color: "yellow", label: "Amarillo", value: thresholds.yellow },
      { color: "red", label: "Rojo", value: thresholds.red },
      { color: "gray", label: "Gris", value: thresholds.gray },
    ];
  }, [thresholds]);

  return (
    <section className="panel section">
      <h3>Colores por capacidad</h3>
      <div className="legend">
        <div className="legend-row"><span className="dot green"></span>Mayor a {thresholds.gray}% y menor a {thresholds.green}%</div>
        <div className="legend-row"><span className="dot yellow"></span>{thresholds.green}% o más y menor a {thresholds.yellow}%</div>
        <div className="legend-row"><span className="dot red"></span>{thresholds.yellow}% o más</div>
        <div className="legend-row"><span className="dot gray"></span>{thresholds.gray}% o menos</div>
      </div>
      <div style={{ display: "grid", gap: "0.5rem", marginTop: "0.75rem" }}>
        {summary.map((item) => (
          <label key={item.color} style={{ display: "flex", alignItems: "center", justifyContent: "space-between", gap: "0.5rem" }}>
            <span style={{ textTransform: "capitalize" }}>{item.label}</span>
            <input
              type="number"
              min={0}
              max={100}
              value={item.value}
              onChange={(event) => handleChange(item.color === "gray" ? "gray" : item.color === "green" ? "green" : item.color === "yellow" ? "yellow" : "red", event.target.value)}
              style={{ width: "4.5rem" }}
            />
          </label>
        ))}
      </div>
      <button
        type="button"
        onClick={() => {
          const reset = DEFAULT_CAPACITY_THRESHOLDS;
          setThresholds(reset);
          setCapacityThresholds(reset);
        }}
        style={{ marginTop: "0.75rem", width: "100%" }}
      >
        Restaurar por defecto
      </button>
    </section>
  );
}
