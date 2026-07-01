import { useState, useMemo } from "react";
import type { Airport, AirportLoads, Flight, Shipment } from "../types";
import { STATUS_COLOR } from "../utils/constants";
import { capacityStatus } from "../utils/calculations";
import { getShipmentsForAirport, getFlightsForAirport, getNextFlightForAirport } from "../utils/airportRelations";
import { AirportFlightsList } from "./AirportFlightsList";
import { AirportShipmentsList } from "./AirportShipmentsList";

interface AirportsTableProps {
  airports: Airport[];
  loads: AirportLoads;
  flights: Flight[];
  shipments: Shipment[];
  simMinute: number;
  selectedAirport?: string | null;
  displayGmtOffset?: number;
  onSelectAirport?: (code: string) => void;
}

export function AirportsTable({
  airports,
  loads,
  flights,
  shipments,
  simMinute,
  selectedAirport: selectedAirportProp,
  displayGmtOffset,
  onSelectAirport,
}: AirportsTableProps) {
  const [search, setSearch] = useState("");
  const [continentFilter, setContinentFilter] = useState("Cualquiera");
  const [localSelectedAirport, setLocalSelectedAirport] = useState<string | null>(null);
  const [flightsModalAirport, setFlightsModalAirport] = useState<string | null>(null);
  const [modalShowFlights, setModalShowFlights] = useState(true);
  const [modalShowShipments, setModalShowShipments] = useState(true);

  const continents = useMemo(
    () => Array.from(new Set(airports.map((a) => a.continent))),
    [airports]
  );

  const [sortBy, setSortBy] = useState<"utilization" | "nextFlight">("utilization");
  const [sortOrder, setSortOrder] = useState<"asc" | "desc">("desc");

  const filtered = useMemo(() => {
    let result = [...airports];

    if (search.trim()) {
      const query = search.toLowerCase();
      result = result.filter(
        (a) =>
          a.code.toLowerCase().includes(query) ||
          a.continent.toLowerCase().includes(query) ||
          a.city.toLowerCase().includes(query)
      );
    }

    if (continentFilter !== "Cualquiera") {
      result = result.filter((a) => a.continent === continentFilter);
    }

    const direction = sortOrder === "asc" ? 1 : -1;
    result.sort((a, b) => {
      if (sortBy === "utilization") {
        const aUtil = a.maxCapacity ? (loads[a.code] || 0) / a.maxCapacity : 0;
        const bUtil = b.maxCapacity ? (loads[b.code] || 0) / b.maxCapacity : 0;
        return (aUtil - bUtil) * direction;
      }

      if (sortBy === "nextFlight") {
        const aNext = getNextFlightForAirport(flights, a.code, simMinute);
        const bNext = getNextFlightForAirport(flights, b.code, simMinute);
        const aTime = aNext?.time ?? Infinity;
        const bTime = bNext?.time ?? Infinity;
        return (aTime - bTime) * direction;
      }

      return 0;
    });


    return result;
  }, [search, continentFilter, airports, loads, sortBy, sortOrder]);

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

      <div className="filters" style={{ display: "flex", gap: "1rem", marginBottom: "1rem" }}>
        <label className="text-sm">
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

      <div className="filters" style={{ display: "flex", gap: "1rem", alignItems: "center", marginBottom: "1rem" }}>
        <label className="text-sm">
          Ordenar por:
          <select
            value={sortBy}
            onChange={(e) => setSortBy(e.target.value as "utilization")}
          >
            <option value="utilization">Ocupación</option>
            <option value="nextFlight">Próximo vuelo</option>
          </select>
        </label>

        <label className="text-sm">
          Dirección:
          <select
            value={sortOrder}
            onChange={(e) => setSortOrder(e.target.value as "asc" | "desc")}
          >
            <option value="desc">Descendente</option>
            <option value="asc">Ascendente</option>
          </select>
        </label>
      </div>

      <div className="table">
        {visible.length === 0 ? (
          <div className="empty-state">No se encontraron resultados.</div>
        ) : (
          visible.map((airport) => {
            const load = loads[airport.code] || 0;
            const utilization = airport.maxCapacity ? load / airport.maxCapacity : 0;
            const status = capacityStatus(utilization);
            const currentSelectedAirport = selectedAirportProp ?? localSelectedAirport;
            const isSelected = currentSelectedAirport === airport.code;

            return (
              <div
                className={`row ${isSelected ? "selected" : ""}`}
                key={airport.code}
                onClick={() => {
                  setLocalSelectedAirport(isSelected ? null : airport.code);
                  onSelectAirport?.(airport.code);
                }}
                style={{ cursor: "pointer" }}
              >
                <span className={`dot ${status}`}></span>
                <div className="row-main">
                  <strong>{`${airport.code} · ${airport.city}`}</strong>
                  <span>{`${load}/${airport.maxCapacity} maletas · ${airport.continent}`}</span>
                </div>
                <span
                  className="capacity-pill"
                  style={{ background: STATUS_COLOR[status] }}
                >
                  <span style={{ marginRight: 8 }}>{`${Math.round(utilization * 100)}%`}</span>
                  <button
                    aria-label={`Ver vuelos ${airport.code}`}
                    className="flights-trigger"
                    onClick={(e) => {
                      e.stopPropagation();
                      setFlightsModalAirport(airport.code);
                    }}
                    style={{
                      background: "transparent",
                      border: "none",
                      color: "inherit",
                      cursor: "pointer",
                      fontWeight: 700,
                      padding: 0,
                    }}
                  >
                    &gt;
                  </button>
                </span>
              </div>
            );
          })
        )}
      </div>

      {flightsModalAirport && (
        <div
          className="modal-overlay"
          style={{
            position: "fixed",
            inset: 0,
            background: "rgba(0,0,0,0.4)",
            display: "flex",
            alignItems: "center",
            justifyContent: "center",
            zIndex: 2000,
          }}
          onClick={() => setFlightsModalAirport(null)}
        >
          <div
            className="modal-content"
            role="dialog"
            aria-modal="true"
            onClick={(e) => e.stopPropagation()}
            style={{
              background: "white",
              padding: "1rem",
              borderRadius: 8,
              maxWidth: "90%",
              maxHeight: "80%",
              overflow: "auto",
            }}
          >
            <div style={{ display: "flex", justifyContent: "space-between", alignItems: "center", marginBottom: 8 }}>
              <h3 style={{ margin: 0 }}>{`${flightsModalAirport}`}</h3>
              <button onClick={() => setFlightsModalAirport(null)} style={{ background: "transparent", border: "none", cursor: "pointer", fontSize: 18 }}>✕</button>
            </div>

            <div style={{ marginBottom: 12 }}>
              <button
                aria-expanded={modalShowFlights}
                onClick={() => setModalShowFlights((s) => !s)}
                style={{
                  background: "transparent",
                  border: "none",
                  cursor: "pointer",
                  fontWeight: 700,
                  padding: 0,
                  marginBottom: 8,
                  color: "#4a5568",
                }}
              >
                {`Vuelos ${modalShowFlights ? "▼" : "►"}`}
              </button>
              {modalShowFlights && (
                <div style={{ marginTop: 8 }}>
                  <AirportFlightsList
                    flights={getFlightsForAirport(flights, flightsModalAirport, simMinute)}
                    displayGmtOffset={displayGmtOffset}
                  />
                </div>
              )}
            </div>

            <div>
              <button
                aria-expanded={modalShowShipments}
                onClick={() => setModalShowShipments((s) => !s)}
                style={{
                  background: "transparent",
                  border: "none",
                  cursor: "pointer",
                  fontWeight: 700,
                  padding: 0,
                  marginBottom: 8,
                  color: "#4a5568",
                }}
              >
                {`Envíos ${modalShowShipments ? "▼" : "►"}`}
              </button>
              {modalShowShipments && (
                <div style={{ marginTop: 8 }}>
                  <AirportShipmentsList shipments={getShipmentsForAirport(shipments, flights, flightsModalAirport)} />
                </div>
              )}
            </div>
          </div>
        </div>
      )}
    </div>
  );
}
