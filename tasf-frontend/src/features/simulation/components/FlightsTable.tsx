import { useState, useMemo } from "react";
import type { Flight } from "../types";
import { STATUS_COLOR } from "../utils/constants";
import { hhmm } from "../utils/formatters";

interface FlightsTableProps {
  flights: Flight[];
}

export function FlightsTable({ flights }: FlightsTableProps) {
  const [search, setSearch] = useState("");
  const [originFilter, setOriginFilter] = useState("Cualquiera");
  const [destinationFilter, setDestinationFilter] = useState("Cualquiera");
  const [sortBy, setSortBy] = useState<"utilization" | "departureMinute" | "arrivalMinute">("utilization");
  const [sortOrder, setSortOrder] = useState<"asc" | "desc">("desc");

  const origins = useMemo(
    () => Array.from(new Set(flights.map((f) => f.origin))),
    [flights]
  );
  const destinations = useMemo(
    () => Array.from(new Set(flights.map((f) => f.destination))),
    [flights]
  );

  const filteredAndSortedFlights = useMemo(() => {
    let result = [...flights];

    if (search.trim()) {
      const query = search.toLowerCase();
      result = result.filter(
        (f) =>
          f.id.toLowerCase().includes(query) ||
          f.origin.toLowerCase().includes(query) ||
          f.destination.toLowerCase().includes(query)
      );
    }

    if (originFilter !== "Cualquiera") {
      result = result.filter((f) => f.origin === originFilter);
    }

    if (destinationFilter !== "Cualquiera") {
      result = result.filter((f) => f.destination === destinationFilter);
    }

    result.sort((a, b) => {
      const aVal = a[sortBy];
      const bVal = b[sortBy];
      return sortOrder === "asc" ? aVal - bVal : bVal - aVal;
    });

    return result;
  }, [flights, search, originFilter, destinationFilter, sortBy, sortOrder]);

  const visibleFlights = filteredAndSortedFlights.slice(0, 10);

  if (!flights.length) {
    return <div className="empty-state">No hay vuelos activos en este minuto.</div>;
  }

  return (
    <div className="flights-table">
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
          Origen:
          <select
            value={originFilter}
            onChange={(e) => setOriginFilter(e.target.value)}
          >
            <option value="Cualquiera">Cualquiera</option>
            {origins.map((o) => (
              <option key={o} value={o}>
                {o}
              </option>
            ))}
          </select>
        </label>

        <label>
          Destino:
          <select
            value={destinationFilter}
            onChange={(e) => setDestinationFilter(e.target.value)}
          >
            <option value="Cualquiera">Cualquiera</option>
            {destinations.map((d) => (
              <option key={d} value={d}>
                {d}
              </option>
            ))}
          </select>
        </label>
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
          Ordenar por:
          <select
            value={sortBy}
            onChange={(e) => setSortBy(e.target.value as "utilization" | "departureMinute" | "arrivalMinute")}
          >
            <option value="utilization">Ocupación</option>
            <option value="departureMinute">Hora de salida</option>
            <option value="arrivalMinute">Hora de llegada</option>
          </select>
        </label>

        <label>
          Dirección:
          <select
            value={sortOrder}
            onChange={(e) => setSortOrder(e.target.value as "asc" | "desc")}
          >
            <option value="asc">Ascendente</option>
            <option value="desc">Descendente</option>
          </select>
        </label>
      </div>

      <div className="table">
        {visibleFlights.length === 0 ? (
          <div className="empty-state">No se encontraron resultados.</div>
        ) : (
          visibleFlights.map((flight) => (
            <div className="row" key={flight.id}>
              <span className={`dot ${flight.status}`}></span>
              <div className="row-main">
                <strong>{`${flight.origin} -> ${flight.destination}`}</strong>
                <span>{`Día ${flight.dayOffset} · ${hhmm(
                  flight.departureMinute
                )}-${hhmm(flight.arrivalMinute)}`}</span>
              </div>
              <span
                className="capacity-pill"
                style={{
                  background: STATUS_COLOR[flight.status],
                }}
              >
                {`${Math.round(flight.utilization * 100)}%`}
              </span>
            </div>
          ))
        )}
      </div>
    </div>
  );
}