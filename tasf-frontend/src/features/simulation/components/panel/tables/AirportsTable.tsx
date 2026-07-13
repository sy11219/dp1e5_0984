import { Fragment, useEffect, useMemo, useRef, useState } from "react";
import type { Airport, AirportLoads, CapacityStatus, Flight, Shipment } from "../../../types";
import { STATUS_COLOR } from "../../../utils/constants";
import { capacityStatus } from "../../../utils/calculations";
import { getFlightsForAirport, getNextFlightByAirport, getShipmentsForAirport } from "../../../utils/airportRelations";
import { AirportDataTable } from "./AirportDataTable";

interface AirportsTableProps {
  airports: Airport[];
  loads: AirportLoads;
  flights: Flight[];
  shipments: Shipment[];
  simMinute: number;
  selectedAirport?: string | null;
  displayGmtOffset?: number;
  onSelectAirport?: (code: string) => void;
  colorFilter?: ColorFilter;
  onColorFilterChange?: (filter: ColorFilter) => void;
}

type ViewType = "incoming" | "outgoing" | "shipments";
type ColorFilter = "Todos" | CapacityStatus;

export function AirportsTable({
  airports,
  loads,
  flights,
  shipments,
  simMinute,
  selectedAirport: selectedAirportProp,
  displayGmtOffset,
  onSelectAirport,
  colorFilter: colorFilterProp,
  onColorFilterChange,
}: AirportsTableProps) {
  const [search, setSearch] = useState("");
  const [continentFilter, setContinentFilter] = useState("Cualquiera");
  const [localColorFilter, setLocalColorFilter] = useState<ColorFilter>("Todos");
  const [localSelectedAirport, setLocalSelectedAirport] = useState<string | null>(null);
  const [expandedAirportCode, setExpandedAirportCode] = useState<string | null>(null);
  const [expandedView, setExpandedView] = useState<ViewType | null>(null);

  const containerRef = useRef<HTMLDivElement>(null);
  const colorFilter = colorFilterProp ?? localColorFilter;
  const setColorFilter = onColorFilterChange ?? setLocalColorFilter;
  const isSelectionControlled = selectedAirportProp !== undefined;

  const continents = useMemo(
    () => Array.from(new Set(airports.map((airport) => airport.continent))).filter(Boolean),
    [airports]
  );

  const [sortBy, setSortBy] = useState<"utilization" | "nextFlight">("utilization");
  const [sortOrder, setSortOrder] = useState<"asc" | "desc">("desc");
  const nextFlightByAirport = useMemo(
    () => sortBy === "nextFlight" ? getNextFlightByAirport(flights, simMinute) : new Map(),
    [flights, simMinute, sortBy]
  );
  const expandedFlights = useMemo(
    () => expandedAirportCode ? getFlightsForAirport(flights, expandedAirportCode, simMinute) : [],
    [expandedAirportCode, flights, simMinute]
  );
  const expandedShipments = useMemo(
    () => expandedAirportCode ? getShipmentsForAirport(shipments, flights, expandedAirportCode) : [],
    [expandedAirportCode, flights, shipments]
  );

  const filtered = useMemo(() => {
    let result = [...airports];

    if (search.trim()) {
      const query = search.toLowerCase();
      result = result.filter(
        (airport) =>
          airport.code.toLowerCase().includes(query) ||
          airport.continent.toLowerCase().includes(query) ||
          airport.city.toLowerCase().includes(query)
      );
    }

    if (continentFilter !== "Cualquiera") {
      result = result.filter((airport) => airport.continent === continentFilter);
    }

    if (colorFilter !== "Todos") {
      result = result.filter((airport) => {
        const load = loads[airport.code] || 0;
        const utilization = airport.maxCapacity ? load / airport.maxCapacity : 0;
        return capacityStatus(utilization) === colorFilter;
      });
    }

    const direction = sortOrder === "asc" ? 1 : -1;
    result.sort((a, b) => {
      if (sortBy === "utilization") {
        const aUtil = a.maxCapacity ? (loads[a.code] || 0) / a.maxCapacity : 0;
        const bUtil = b.maxCapacity ? (loads[b.code] || 0) / b.maxCapacity : 0;
        return (aUtil - bUtil) * direction;
      }

      const aNext = nextFlightByAirport.get(a.code);
      const bNext = nextFlightByAirport.get(b.code);
      const aTime = aNext?.time ?? Infinity;
      const bTime = bNext?.time ?? Infinity;
      return (aTime - bTime) * direction;
    });

    return result;
  }, [airports, colorFilter, continentFilter, loads, nextFlightByAirport, search, sortBy, sortOrder]);

  const visible = filtered.slice(0, 10);

  useEffect(() => {
    if (!expandedView) return;
    const handleClickOutside = (event: MouseEvent) => {
      if (containerRef.current && !containerRef.current.contains(event.target as Node)) {
        setExpandedView(null);
        setExpandedAirportCode(null);
      }
    };
    const timer = window.setTimeout(() => {
      document.addEventListener("mousedown", handleClickOutside);
    }, 100);
    return () => {
      window.clearTimeout(timer);
      document.removeEventListener("mousedown", handleClickOutside);
    };
  }, [expandedView]);

  if (!airports.length) {
    return <div className="empty-state">No hay aeropuertos activos.</div>;
  }

  const selectAirport = (code: string, isSelected: boolean) => {
    if (!isSelectionControlled) setLocalSelectedAirport(isSelected ? null : code);
    onSelectAirport?.(code);
  };

  return (
    <div className="airports-table" ref={containerRef}>
      {expandedView && expandedAirportCode ? (
        <div style={{ padding: "0.5rem" }}>
          <div style={{ display: "flex", justifyContent: "space-between", alignItems: "center", marginBottom: 12 }}>
            <h3 style={{ margin: 0, fontSize: 16 }}>
              {expandedAirportCode} - {expandedView === "incoming" ? "Vuelos entrantes" : expandedView === "outgoing" ? "Vuelos salientes" : "Envíos"}
            </h3>
            <button
              onClick={() => {
                setExpandedView(null);
                setExpandedAirportCode(null);
              }}
              style={{ background: "transparent", border: "none", cursor: "pointer", fontSize: 18 }}
              aria-label="Cerrar"
            >
              x
            </button>
          </div>

          <AirportDataTable
            viewType={expandedView}
            flights={expandedFlights}
            shipments={expandedShipments}
            airportCode={expandedAirportCode}
            displayGmtOffset={displayGmtOffset}
          />
        </div>
      ) : (
        <>
          <div className="search-bar" style={{ marginBottom: "0.5rem" }}>
            <input
              type="text"
              placeholder="Buscar..."
              value={search}
              onChange={(event) => setSearch(event.target.value)}
              style={{ width: "100%" }}
            />
          </div>

          <div className="filters" style={{ display: "flex", gap: "1rem", marginBottom: "1rem" }}>
            <label className="text-sm">
              Continente:
              <select
                value={continentFilter}
                onChange={(event) => setContinentFilter(event.target.value)}
              >
                <option value="Cualquiera">Cualquiera</option>
                {continents.map((continent) => (
                  <option key={continent} value={continent}>
                    {continent}
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
                onChange={(event) => setSortBy(event.target.value as "utilization" | "nextFlight")}
              >
                <option value="utilization">Ocupación</option>
                <option value="nextFlight">Próximo vuelo</option>
              </select>
            </label>

            <label className="text-sm">
              Dirección:
              <select
                value={sortOrder}
                onChange={(event) => setSortOrder(event.target.value as "asc" | "desc")}
              >
                <option value="desc">Descendente</option>
                <option value="asc">Ascendente</option>
              </select>
            </label>

            <label className="text-sm">
              Color:
              <select
                value={colorFilter}
                onChange={(event) => setColorFilter(event.target.value as ColorFilter)}
              >
                <option value="Todos">Todos</option>
                <option value="green">Verde</option>
                <option value="yellow">Amarillo</option>
                <option value="red">Rojo</option>
                <option value="gray">Gris</option>
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
                const currentSelectedAirport = isSelectionControlled ? selectedAirportProp : localSelectedAirport;
                const isSelected = currentSelectedAirport === airport.code;

                return (
                  <Fragment key={airport.code}>
                    <div
                      className={`row ${isSelected ? "selected" : ""}`}
                      onClick={() => selectAirport(airport.code, isSelected)}
                      style={{ cursor: "pointer", position: "relative" }}
                    >
                      <span className={`dot ${status}`}></span>
                      <div className="row-main">
                        <strong>{`${airport.code} - ${airport.city}`}</strong>
                        <span>{`${load}/${airport.maxCapacity} maletas - ${airport.continent}`}</span>
                      </div>
                      <span className="capacity-pill" style={{ background: STATUS_COLOR[status] }}>
                        {`${Math.round(utilization * 100)}%`}
                      </span>
                    </div>
                    {isSelected && (
                      <div className="table-action-bar" aria-label={`Acciones de ${airport.code}`}>
                        <button
                          type="button"
                          className="table-action-button"
                          onClick={() => {
                            setExpandedAirportCode(airport.code);
                            setExpandedView("incoming");
                          }}
                        >
                          Vuelos entrantes
                        </button>
                        <button
                          type="button"
                          className="table-action-button"
                          onClick={() => {
                            setExpandedAirportCode(airport.code);
                            setExpandedView("outgoing");
                          }}
                        >
                          Vuelos salientes
                        </button>
                        <button
                          type="button"
                          className="table-action-button"
                          onClick={() => {
                            setExpandedAirportCode(airport.code);
                            setExpandedView("shipments");
                          }}
                        >
                          Envíos
                        </button>
                        <button
                          type="button"
                          className="table-action-button table-action-button-ghost"
                          onClick={() => selectAirport(airport.code, true)}
                        >
                          Quitar selección
                        </button>
                      </div>
                    )}
                  </Fragment>
                );
              })
            )}
          </div>
        </>
      )}
    </div>
  );
}
