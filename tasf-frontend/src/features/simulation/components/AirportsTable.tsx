import { useState, useMemo } from "react";
import type { Airport, AirportLoads } from "../types";
import { STATUS_COLOR } from "../utils/constants";
import { capacityStatus } from "../utils/calculations";

interface AirportsTableProps {
  airports: Airport[];
  loads: AirportLoads;
}

export function AirportsTable({ airports, loads }: AirportsTableProps) {
  const [search, setSearch] = useState("");
  const [continentFilter, setContinentFilter] = useState("Cualquiera");

  const continents = useMemo(
    () => Array.from(new Set(airports.map((a) => a.continent))),
    [airports]
  );

  const ordered = useMemo(
    () =>
      [...airports].sort(
        (a, b) =>
          (loads[b.code] || 0) / b.maxCapacity -
          (loads[a.code] || 0) / a.maxCapacity
      ),
    [airports, loads]
  );

  // Aplica búsqueda + filtro por continente
  const filtered = useMemo(() => {
    let result = ordered;

    if (search.trim()) {
      const query = search.toLowerCase();
      result = result.filter(
        (a) =>
          a.code.toLowerCase().includes(query) ||
          a.continent.toLowerCase().includes(query)
      );
    }

    if (continentFilter !== "Cualquiera") {
      result = result.filter((a) => a.continent === continentFilter);
    }

    return result;
  }, [search, continentFilter, ordered]);

  const visible = filtered.slice(0, 10);

  if (!airports.length) {
    return <div className="empty-state">No hay aeropuertos activos.</div>;
  }

  return (
    <div className="airports-table">
      <div className="search-bar" style={{ marginBottom: "0.5rem" }}>
        <input
          type="text"
          placeholder="Buscar..."
          value={search}
          onChange={(e) => setSearch(e.target.value)}
          style={{ width: "100%" }}
        />
      </div>

      <div
        className="filters"
        style={{
          display: "flex",
          gap: "1rem",
          alignItems: "center",
          marginBottom: "1rem",
        }}
      >
        <label>
          Continente:
          <select
            value={continentFilter}
            onChange={(e) => setContinentFilter(e.target.value)}
          >
            <option value="Cualquiera">Cualquiera</option>
            {continents.map((c) => (
              <option key={c} value={c}>
                {c}
              </option>
            ))}
          </select>
        </label>
      </div>

      <div className="table">
        {visible.length === 0 ? (
          <div className="empty-state">No se encontraron resultados.</div>
        ) : (
          visible.map((airport) => {
            const load = loads[airport.code] || 0;
            const utilization = airport.maxCapacity
              ? load / airport.maxCapacity
              : 0;
            const status = capacityStatus(utilization);

            return (
              <div className="row" key={airport.code}>
                <span className={`dot ${status}`}></span>
                <div className="row-main">
                  <strong>{`${airport.code} · ${airport.city}`}</strong>
                  <span>{`${load}/${airport.maxCapacity} maletas · ${airport.continent}`}</span>
                </div>
                <span
                  className="capacity-pill"
                  style={{
                    background: STATUS_COLOR[status],
                  }}
                >
                  {`${Math.round(utilization * 100)}%`}
                </span>
              </div>
            );
          })
        )}
      </div>
    </div>
  );
}
