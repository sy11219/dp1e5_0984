import { useMemo, useState } from "react";
import type { Flight, Shipment } from "../types";
import { STATUS_COLOR } from "../utils/constants";
import { hhmm } from "../utils/formatters";

interface FlightsTableProps {
  flights: Flight[];
  activeFlightIds: Set<string>;
  shipments: Shipment[];
  simMinute: number;
  selectedFlightId?: string | null;
  onSelectFlight?: (id: string) => void;
  displayGmtOffset?: number;
}

export function FlightsTable({
  flights,
  activeFlightIds,
  shipments,
  simMinute,
  selectedFlightId,
  onSelectFlight,
  displayGmtOffset,
}: FlightsTableProps) {
  const [search, setSearch] = useState("");
  const [originFilter, setOriginFilter] = useState("Cualquiera");
  const [destinationFilter, setDestinationFilter] = useState("Cualquiera");
  const [statusFilter, setStatusFilter] = useState<"Todos" | "Activos" | "Inactivos">("Todos");
  const [sortBy, setSortBy] = useState<
    "utilization" | "departureMinute" | "arrivalMinute" | "origin" | "destination"
  >("utilization");
  const [sortOrder, setSortOrder] = useState<"asc" | "desc">("desc");
  const [selectedFlightShipments, setSelectedFlightShipments] = useState<Flight | null>(null);
  const [viewMode, setViewMode] = useState<"flights" | "flight-shipments">("flights");

  const origins = useMemo(() => Array.from(new Set(flights.map((f) => f.origin))), [flights]);
  const destinations = useMemo(
    () => Array.from(new Set(flights.map((f) => f.destination))),
    [flights]
  );

  const relatedShipments = useMemo(() => {
    if (!selectedFlightShipments) return [];
    return shipments
      .filter((shipment) => {
        if (!shipment.flightIds.includes(selectedFlightShipments.id)) return false;
        if (shipment.requestMinute > simMinute) return false;
        if (!shipment.planned) return true;
        return simMinute <= shipment.estimatedArrival;
      })
      .sort((a, b) => a.requestMinute - b.requestMinute);
  }, [shipments, selectedFlightShipments, simMinute]);

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

      if (aActive !== bActive) return aActive ? -1 : 1;

      const aVal = a[sortBy];
      const bVal = b[sortBy];
      if (typeof aVal === "string" && typeof bVal === "string") {
        return sortOrder === "asc" ? aVal.localeCompare(bVal) : bVal.localeCompare(aVal);
      }
      return sortOrder === "asc"
        ? (aVal as number) - (bVal as number)
        : (bVal as number) - (aVal as number);
    });

    return result;
  }, [flights, search, originFilter, destinationFilter, statusFilter, sortBy, sortOrder, activeFlightIds]);

  const visibleFlights = filteredAndSortedFlights.slice(0, 10);
  const gmtOffset = displayGmtOffset ?? 0;

  if (viewMode === "flight-shipments" && selectedFlightShipments) {
    return (
      <div className="flights-table">
        <div style={{ display: "flex", justifyContent: "space-between", alignItems: "center", marginBottom: "1rem" }}>
          <h3 style={{ margin: 0 }}>Envios del vuelo {selectedFlightShipments.id}</h3>
          <button
            type="button"
            onClick={() => setViewMode("flights")}
            style={{ cursor: "pointer", background: "transparent", border: "1px solid #cbd5e0", borderRadius: 6, padding: "0.4rem 0.8rem" }}
          >
            Volver
          </button>
        </div>
        <div className="table">
          {relatedShipments.length === 0 ? (
            <div className="empty-state">No se encontraron envios relacionados.</div>
          ) : (
            relatedShipments.map((shipment) => (
              <div className="row" key={shipment.id}>
                <div className="row-main">
                  <strong>{shipment.id}</strong>
                  <span>{shipment.clientId}</span>
                </div>
                <span style={{ color: "#000", textAlign: "right" }}>
                  {shipment.suitcases}
                </span>
              </div>
            ))
          )}
        </div>
      </div>
    );
  }

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

      <div className="filters" style={{ display: "flex", gap: "1rem", alignItems: "center", marginBottom: "1rem" }}>
        <label className="text-sm">
          Estado:
          <select value={statusFilter} onChange={(e) => setStatusFilter(e.target.value as "Todos" | "Activos" | "Inactivos")}>
            <option value="Todos">Todos</option>
            <option value="Activos">Activos</option>
            <option value="Inactivos">Inactivos</option>
          </select>
        </label>

        <label className="text-sm">
          Origen:
          <select value={originFilter} onChange={(e) => setOriginFilter(e.target.value)}>
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
          <select value={destinationFilter} onChange={(e) => setDestinationFilter(e.target.value)}>
            <option value="Cualquiera">Cualquiera</option>
            {destinations.map((d) => (
              <option key={d} value={d}>
                {d}
              </option>
            ))}
          </select>
        </label>
      </div>

      <div className="filters" style={{ display: "flex", gap: "1rem", alignItems: "center", marginBottom: "1rem" }}>
        <label className="text-sm">
          Ordenar por:
          <select value={sortBy} onChange={(e) => setSortBy(e.target.value as "utilization" | "departureMinute" | "arrivalMinute" | "origin" | "destination")}>
            <option value="utilization">Ocupacion</option>
            <option value="departureMinute">Hora de salida</option>
            <option value="arrivalMinute">Hora de llegada</option>
            <option value="origin">Origen</option>
            <option value="destination">Destino</option>
          </select>
        </label>

        <label className="text-sm">
          Direccion:
          <select value={sortOrder} onChange={(e) => setSortOrder(e.target.value as "asc" | "desc")}>
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
                  <span>{`Dia ${flight.dayOffset} - ${hhmm(flight.departureMinute, gmtOffset)}-${hhmm(flight.arrivalMinute, gmtOffset)}`}</span>
                </div>
                <span
                  className="capacity-pill"
                  style={{
                    background: STATUS_COLOR[flight.status],
                    display: "inline-flex",
                    alignItems: "center",
                    gap: "0.35rem",
                    padding: "0.2rem 0.5rem",
                    minWidth: "auto",
                    whiteSpace: "nowrap",
                  }}
                >
                  {active ? `${Math.round(flight.utilization * 100)}%` : "Inactivo"}
                  {active && (
                    <button
                      type="button"
                      aria-label={`Ver envios del vuelo ${flight.id}`}
                      onClick={(e) => {
                        e.stopPropagation();
                        setSelectedFlightShipments(flight);
                        setViewMode("flight-shipments");
                      }}
                      style={{
                        background: "transparent",
                        border: "none",
                        color: "inherit",
                        cursor: "pointer",
                        fontWeight: 700,
                        padding: 0,
                        lineHeight: 1,
                      }}
                    >
                      &gt;
                    </button>
                  )}
                </span>
              </div>
            );
          })
        )}
      </div>
    </div>
  );
}
