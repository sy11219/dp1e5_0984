import { useState, useMemo } from "react";
import type { Airport, AirportLoads, Flight, Shipment } from "../types";
import { STATUS_COLOR } from "../utils/constants";
import { capacityStatus } from "../utils/calculations";
import { getShipmentsForAirport, getFlightsForAirport } from "../utils/airportRelations";
import { AirportFlightsList } from "./AirportFlightsList";
import { AirportShipmentsList } from "./AirportShipmentsList";

interface AirportsTableProps {
  airports: Airport[];
  loads: AirportLoads;
  flights: Flight[];
  shipments: Shipment[];
}

export function AirportsTable({ airports, loads, flights, shipments }: AirportsTableProps) {
  const [search, setSearch] = useState("");
  const [continentFilter, setContinentFilter] = useState("Cualquiera");
  const [selectedAirport, setSelectedAirport] = useState<string | null>(null);
  const [flightsModalAirport, setFlightsModalAirport] = useState<string | null>(null);
  const [modalShowFlights, setModalShowFlights] = useState(true);
  const [modalShowShipments, setModalShowShipments] = useState(true);

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

      <div className="table">
        {visible.length === 0 ? (
          <div className="empty-state">No se encontraron resultados.</div>
        ) : (
          visible.map((airport) => {
            const load = loads[airport.code] || 0;
            const utilization = airport.maxCapacity ? load / airport.maxCapacity : 0;
            const status = capacityStatus(utilization);
            const isSelected = selectedAirport === airport.code;

            return (
              <div
                className={`row ${isSelected ? "selected" : ""}`}
                key={airport.code}
                onClick={() => setSelectedAirport(isSelected ? null : airport.code)}
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
                  <AirportFlightsList flights={getFlightsForAirport(flights, flightsModalAirport)} />
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
