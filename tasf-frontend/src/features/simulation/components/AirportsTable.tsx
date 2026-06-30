import { useState, useMemo, useRef, useEffect } from "react";
import type { Airport, AirportLoads, Flight, Shipment } from "../types";
import { STATUS_COLOR } from "../utils/constants";
import { capacityStatus } from "../utils/calculations";
import { getShipmentsForAirport, getFlightsForAirport, getNextFlightForAirport } from "../utils/airportRelations";
import { AirportDataTable } from "./AirportDataTable";

interface AirportsTableProps {
  airports: Airport[];
  loads: AirportLoads;
  flights: Flight[];
  shipments: Shipment[];
  simMinute: number;
  selectedAirport?: string | null;
  onSelectAirport?: (code: string) => void;
}

type ViewType = "incoming" | "outgoing" | "shipments";

export function AirportsTable({
  airports,
  loads,
  flights,
  shipments,
  simMinute,
  selectedAirport: selectedAirportProp,
  onSelectAirport,
}: AirportsTableProps) {
  const [search, setSearch] = useState("");
  const [continentFilter, setContinentFilter] = useState("Cualquiera");
  const [localSelectedAirport, setLocalSelectedAirport] = useState<string | null>(null);
  
  const [expandedAirportCode, setExpandedAirportCode] = useState<string | null>(null);
  const [expandedView, setExpandedView] = useState<ViewType | null>(null);
  const [selectorOpen, setSelectorOpen] = useState(false);
  
  const containerRef = useRef<HTMLDivElement>(null);
  const selectorRef = useRef<HTMLDivElement>(null);

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

  // Cerrar selector al hacer clic fuera
  useEffect(() => {
    const handleClickOutside = (event: MouseEvent) => {
      if (selectorRef.current && !selectorRef.current.contains(event.target as Node)) {
        setSelectorOpen(false);
      }
    };
    document.addEventListener("mousedown", handleClickOutside);
    return () => document.removeEventListener("mousedown", handleClickOutside);
  }, []);

  // Cerrar vista expandida al hacer clic fuera
  useEffect(() => {
    if (!expandedView) return;
    const handleClickOutside = (event: MouseEvent) => {
      if (containerRef.current && !containerRef.current.contains(event.target as Node)) {
        setExpandedView(null);
        setExpandedAirportCode(null);
      }
    };
    const timer = setTimeout(() => {
      document.addEventListener("mousedown", handleClickOutside);
    }, 100);
    return () => {
      clearTimeout(timer);
      document.removeEventListener("mousedown", handleClickOutside);
    };
  }, [expandedView]);

  if (!airports.length) {
    return <div className="empty-state">No hay aeropuertos activos.</div>;
  }

  return (
    <div className="airports-table" ref={containerRef}>
      {expandedView && expandedAirportCode ? (
        <div style={{ padding: "0.5rem" }}>
          <div style={{ display: "flex", justifyContent: "space-between", alignItems: "center", marginBottom: 12 }}>
            <h3 style={{ margin: 0, fontSize: 16 }}>
              {expandedAirportCode} - {expandedView === 'incoming' ? 'Vuelos Entrantes' : expandedView === 'outgoing' ? 'Vuelos Salientes' : 'Envíos'}
            </h3>
            <button 
              onClick={() => { setExpandedView(null); setExpandedAirportCode(null); }} 
              style={{ background: "transparent", border: "none", cursor: "pointer", fontSize: 18 }}
              aria-label="Cerrar"
            >
              ✕
            </button>
          </div>
          
          <AirportDataTable
            viewType={expandedView}
            flights={getFlightsForAirport(flights, expandedAirportCode, simMinute)}
            shipments={getShipmentsForAirport(shipments, flights, expandedAirportCode)}
            airportCode={expandedAirportCode}
          />
        </div>
      ) : (
        <>
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
                    style={{ cursor: "pointer", position: "relative" }}
                  >
                    <span className={`dot ${status}`}></span>
                    <div className="row-main">
                      <strong>{`${airport.code} · ${airport.city}`}</strong>
                      <span>{`${load}/${airport.maxCapacity} maletas · ${airport.continent}`}</span>
                    </div>
                    <span className="capacity-pill" style={{ background: STATUS_COLOR[status], position: "relative" }}>
                      <span style={{ marginRight: 8 }}>{`${Math.round(utilization * 100)}%`}</span>
                      <button
                        aria-label={`Ver opciones ${airport.code}`}
                        className="flights-trigger"
                        onClick={(e) => {
                          e.stopPropagation();
                          if (expandedAirportCode === airport.code) {
                            setSelectorOpen(!selectorOpen);
                          } else {
                            setExpandedAirportCode(airport.code);
                            setSelectorOpen(true);
                          }
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
                      
                      {/* POPOVER */}
                      {selectorOpen && expandedAirportCode === airport.code && (
                        <div 
                          ref={selectorRef}
                          style={{
                            position: "absolute",
                            right: 0,
                            top: "calc(100% + 4px)",
                            background: "white",
                            border: "1px solid #cbd5e0",
                            borderRadius: 6,
                            boxShadow: "0 4px 12px rgba(0,0,0,0.15)",
                            zIndex: 1000,
                            minWidth: 180,
                            overflow: "visible",
                          }}
                          onClick={(e) => e.stopPropagation()}
                        >
                          <button
                            onClick={() => { setExpandedView('incoming'); setSelectorOpen(false); }}
                            style={{
                              display: "block",
                              width: "100%",
                              padding: "10px 16px",
                              background: "transparent",
                              border: "none",
                              borderBottom: "1px solid #e2e8f0",
                              cursor: "pointer",
                              textAlign: "left",
                              fontSize: 14,
                              color: "#2d3748",
                              fontWeight: 500,
                            }}
                            onMouseEnter={(e) => e.currentTarget.style.background = "#f7fafc"}
                            onMouseLeave={(e) => e.currentTarget.style.background = "transparent"}
                          >
                            Vuelos entrantes
                          </button>
                          <button
                            onClick={() => { setExpandedView('outgoing'); setSelectorOpen(false); }}
                            style={{
                              display: "block",
                              width: "100%",
                              padding: "10px 16px",
                              background: "transparent",
                              border: "none",
                              borderBottom: "1px solid #e2e8f0",
                              cursor: "pointer",
                              textAlign: "left",
                              fontSize: 14,
                              color: "#2d3748",
                              fontWeight: 500,
                            }}
                            onMouseEnter={(e) => e.currentTarget.style.background = "#f7fafc"}
                            onMouseLeave={(e) => e.currentTarget.style.background = "transparent"}
                          >
                            Vuelos salientes
                          </button>
                          <button
                            onClick={() => { setExpandedView('shipments'); setSelectorOpen(false); }}
                            style={{
                              display: "block",
                              width: "100%",
                              padding: "10px 16px",
                              background: "transparent",
                              border: "none",
                              cursor: "pointer",
                              textAlign: "left",
                              fontSize: 14,
                              color: "#2d3748",
                              fontWeight: 500,
                            }}
                            onMouseEnter={(e) => e.currentTarget.style.background = "#f7fafc"}
                            onMouseLeave={(e) => e.currentTarget.style.background = "transparent"}
                          >
                            Envíos
                          </button>
                        </div>
                      )}
                    </span>
                  </div>
                );
              })
            )}
          </div>
        </>
      )}
    </div>
  );
}