import { useState, useMemo } from "react";
import type { Flight } from "../types";
import { STATUS_COLOR } from "../utils/constants";
import { hhmm } from "../utils/formatters";

interface FlightsTableProps {
  flights: Flight[];
  activeFlightIds: Set<string>;
  selectedFlightId?: string | null;
  onSelectFlight?: (id: string) => void;
}

export function FlightsTable({
  flights,
  activeFlightIds,
  selectedFlightId,
  onSelectFlight,
}: FlightsTableProps) {
  const [search, setSearch] = useState("");
  const [originFilter, setOriginFilter] = useState("Cualquiera");
  const [destinationFilter, setDestinationFilter] = useState("Cualquiera");
  const [statusFilter, setStatusFilter] = useState<"Todos" | "Activos" | "Inactivos">("Todos");
  const [sortBy, setSortBy] = useState<"utilization" | "departureMinute" | "arrivalMinute" | "origin" | "destination">("utilization");
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

    if (statusFilter === "Activos") {
      result = result.filter((f) => activeFlightIds.has(f.id));
    } else if (statusFilter === "Inactivos") {
      result = result.filter((f) => !activeFlightIds.has(f.id));
    }

    result.sort((a, b) => {
      const aActive = activeFlightIds.has(a.id);
      const bActive = activeFlightIds.has(b.id);

      if (aActive !== bActive) {
        return aActive ? -1 : 1;
      }

      const aVal = a[sortBy];
      const bVal = b[sortBy];
      if (typeof aVal === "string" && typeof bVal === "string") {
        return sortOrder === "asc"
          ? aVal.localeCompare(bVal)
          : bVal.localeCompare(aVal);
      }
      else return sortOrder === "asc" ? (aVal as number) - (bVal as number) : (bVal as number) - (aVal as number);
    });

    return result;
  }, [flights, search, originFilter, destinationFilter, statusFilter, sortBy, sortOrder, activeFlightIds]);

  const visibleFlights = filteredAndSortedFlights.slice(0, 10);

  if (!flights.length) {
    return <div className="empty-state">No hay vuelos registrados.</div>;
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
        <label className="text-sm">
          Estado:
          <select
            value={statusFilter}
            onChange={(e) => setStatusFilter(e.target.value as "Todos" | "Activos" | "Inactivos")}
          >
            <option value="Todos">Todos</option>
            <option value="Activos">Activos</option>
            <option value="Inactivos">Inactivos</option>
          </select>
        </label>

        <label className="text-sm">
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

        <label className="text-sm">
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
        <label className="text-sm">
          Ordenar por:
          <select
            value={sortBy}
            onChange={(e) => setSortBy(e.target.value as "utilization" | "departureMinute" | "arrivalMinute" | "origin" | "destination")}
          >
            <option value="utilization">Ocupación</option>
            <option value="departureMinute">Hora de salida</option>
            <option value="arrivalMinute">Hora de llegada</option>
            <option value="origin">Origen</option>
            <option value="destination">Destino</option>
          </select>
        </label>

        <label className="text-sm">
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
          visibleFlights.map((flight) => {
            const active = activeFlightIds.has(flight.id);

            return (
              <div
                className={[
                  "row",
                  !active ? "row-inactive" : "",
                  selectedFlightId === flight.id ? "selected" : "",
                ].filter(Boolean).join(" ")}
                key={flight.id}
                onClick={() => onSelectFlight?.(flight.id)}
                style={{
                  opacity: active ? 1 : 0.6,
                  cursor: onSelectFlight ? "pointer" : undefined,
                }}
              >
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
                  {active
                    ? `${Math.round(flight.utilization * 100)}%`
                    : "Inactivo"
                  }
                </span>
              </div>
            );
          })
        )}
      </div>
    </div>
  );
}
