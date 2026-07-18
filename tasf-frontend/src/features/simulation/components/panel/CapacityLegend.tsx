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
      {
        color: "gray",
        description: `${thresholds.gray}% o menos`,
        value: thresholds.gray,
      },
      {
        color: "green",
        description: `Mayor a ${thresholds.gray}% y menor a ${thresholds.green}%`,
        value: thresholds.green,
      },
      {
        color: "yellow",
        description: `${thresholds.green}% o más y menor a ${thresholds.yellow}%`,
        value: thresholds.yellow,
      },
      {
        color: "red",
        description: `${thresholds.yellow}% o más`,
        value: thresholds.red,
      },
    ];
  }, [thresholds]);

  return (
    <section className="panel section">
      <h3 style={{ fontSize: "1rem", marginBottom: "0.5rem" }}>Colores por capacidad</h3>
      <div style={{ display: "grid", gap: "0.45rem", marginTop: "0.6rem" }}>
        {summary.map((item) => (
          <div
            key={item.color}
            style={{
              display: "grid",
              gridTemplateColumns: "1fr auto",
              alignItems: "center",
              gap: "0.65rem",
              padding: "0.55rem 0.7rem",
              border: "1px solid #e2e8f0",
              borderRadius: "0.75rem",
              background: "#fff",
            }}
          >
            <div style={{ display: "flex", alignItems: "center", gap: "0.65rem", color: "#1a202c", fontSize: "0.9rem" }}>
              <span
                className={`dot ${item.color}`}
                style={{ width: "0.8rem", height: "0.8rem", flexShrink: 0 }}
              />
              <span>{item.description}</span>
            </div>
            <div style={{ display: "flex", alignItems: "center", gap: "0.35rem" }}>
              <input
                type="number"
                min={0}
                max={100}
                value={item.value}
                onChange={(event) =>
                  handleChange(
                    item.color === "gray"
                      ? "gray"
                      : item.color === "green"
                      ? "green"
                      : item.color === "yellow"
                      ? "yellow"
                      : "red",
                    event.target.value
                  )
                }
                style={{
                  width: "4.3rem",
                  padding: "0.35rem 0.5rem",
                  borderRadius: "0.6rem",
                  border: "1px solid #cbd5e0",
                  background: "#f8fafc",
                  textAlign: "right",
                  fontSize: "0.9rem",
                }}
              />
              <span style={{ color: "#718096", fontSize: "0.9rem" }}>%</span>
            </div>
          </div>
        ))}
      </div>
      <button
        type="button"
        onClick={() => {
          const reset = DEFAULT_CAPACITY_THRESHOLDS;
          setThresholds(reset);
          setCapacityThresholds(reset);
        }}
        style={{
          marginTop: "0.7rem",
          width: "100%",
          borderRadius: "0.75rem",
          border: "1px solid #cbd5e0",
          background: "#f7fafc",
          color: "#2d3748",
          fontWeight: 700,
          padding: "0.65rem 0.9rem",
          cursor: "pointer",
          fontSize: "0.95rem",
        }}
      >
        Restaurar por defecto
      </button>
    </section>
  );
}
