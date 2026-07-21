import { Fragment, useEffect, useMemo, useRef, useState } from "react";
import { getBatchShipmentsPageRequest } from "../../../../../api/simulationApi";
import type { CapacityStatus, Flight, Shipment, SimulationData } from "../../../types";
import { STATUS_COLOR } from "../../../utils/constants";
import { capacityStatus } from "../../../utils/calculations";
import { formatFlightMoment } from "../../../utils/formatters";

interface FlightsTableProps {
  flights: Flight[];
  activeFlightIds: Set<string>;
  shipments: Shipment[];
  data?: SimulationData | null;
  selectedFlightId?: string | null;
  onSelectFlight?: (id: string) => void;
  displayGmtOffset?: number;
  colorFilter?: ColorFilter;
  onColorFilterChange?: (filter: ColorFilter) => void;
  hideColorFilter?: boolean;
}

type ColorFilter = "Todos" | CapacityStatus;
const PAGE_SIZE_FLIGHTS = 10;

export function FlightsTable({
  flights,
  activeFlightIds,
  shipments,
  data,
  selectedFlightId,
  onSelectFlight,
  displayGmtOffset,
  colorFilter: colorFilterProp,
  onColorFilterChange,
  hideColorFilter,
}: FlightsTableProps) {
  const [search, setSearch] = useState("");
  const [originFilter, setOriginFilter] = useState("Cualquiera");
  const [destinationFilter, setDestinationFilter] = useState("Cualquiera");
  const [statusFilter, setStatusFilter] = useState<"Todos" | "Activos" | "No iniciados">("Todos");
  const [localColorFilter, setLocalColorFilter] = useState<ColorFilter>("Todos");
  const [sortBy, setSortBy] = useState<
    "utilization" | "departureMinute" | "arrivalMinute" | "origin" | "destination"
  >("utilization");
  const [sortOrder, setSortOrder] = useState<"asc" | "desc">("desc");
  const [selectedFlightShipments, setSelectedFlightShipments] = useState<Flight | null>(null);
  const [viewMode, setViewMode] = useState<"flights" | "flight-shipments">("flights");
  const [page, setPage] = useState(1);
  const [remoteFlightId, setRemoteFlightId] = useState<string | null>(null);
  const [remoteFlightShipments, setRemoteFlightShipments] = useState<Shipment[]>([]);
  const [remoteFlightShipmentsLoading, setRemoteFlightShipmentsLoading] = useState(false);
  const [remoteFlightShipmentsError, setRemoteFlightShipmentsError] = useState("");
  const selectedFlightRowRef = useRef<HTMLDivElement | null>(null);
  const lastPagedSelectedFlightId = useRef<string | null>(null);
  const colorFilter = colorFilterProp ?? localColorFilter;
  const setColorFilter = onColorFilterChange ?? setLocalColorFilter;
  const batchSimulationId = data?.simulationId;
  const usesRemoteBatchShipments = Boolean(
    batchSimulationId && (data?.planningWindowMinutes ?? 0) > 2
  );

  const cancelledFlightKeys = useMemo(() => {
    return new Set(data?.cancelledFlightIds ?? []);
  }, [data?.cancelledFlightIds]);

  const airportOptions = useMemo(() => {
    const set = new Set<string>();
    for (const flight of flights) {
      set.add(flight.origin);
      set.add(flight.destination);
    }
    return [...set].sort((a, b) => a.localeCompare(b, undefined, { sensitivity: "base" }));
  }, [flights]);

  const selectedFlight = useMemo(
    () => (selectedFlightId ? flights.find((flight) => flight.id === selectedFlightId) ?? null : null),
    [flights, selectedFlightId]
  );

  useEffect(() => {
    if (!usesRemoteBatchShipments || !batchSimulationId || !selectedFlight) {
      setRemoteFlightId(null);
      setRemoteFlightShipments([]);
      setRemoteFlightShipmentsLoading(false);
      setRemoteFlightShipmentsError("");
      return;
    }

    let cancelled = false;
    const flightId = selectedFlight.id;
    setRemoteFlightId(flightId);
    setRemoteFlightShipments([]);
    setRemoteFlightShipmentsLoading(true);
    setRemoteFlightShipmentsError("");

    void (async () => {
      try {
        const pageSize = 100;
        const firstPage = await getBatchShipmentsPageRequest(batchSimulationId, {
          page: 1,
          pageSize,
          search: flightId,
        }, data?.scenario);
        const remainingPages = await Promise.all(
          Array.from({ length: Math.max(0, firstPage.totalPages - 1) }, (_, index) =>
            getBatchShipmentsPageRequest(batchSimulationId, {
              page: index + 2,
              pageSize,
              search: flightId,
            }, data?.scenario)
          )
        );
        const matchingShipments = [firstPage, ...remainingPages]
          .flatMap((page) => page.items)
          .filter((shipment) => shipment.flightIds.includes(flightId));

        if (!cancelled) setRemoteFlightShipments(matchingShipments);
      } catch (error) {
        if (!cancelled) {
          setRemoteFlightShipmentsError(
            error instanceof Error ? error.message : "No se pudieron cargar los envíos del vuelo."
          );
        }
      } finally {
        if (!cancelled) setRemoteFlightShipmentsLoading(false);
      }
    })();

    return () => {
      cancelled = true;
    };
  }, [batchSimulationId, data?.scenario, selectedFlight, usesRemoteBatchShipments]);

  const relatedShipments = useMemo(() => {
    if (!selectedFlightShipments) return [];
    const source =
      usesRemoteBatchShipments && remoteFlightId === selectedFlightShipments.id
        ? remoteFlightShipments
        : shipments;
    return source
      .filter((shipment) => shipment.flightIds.includes(selectedFlightShipments.id))
      .sort((a, b) => a.requestMinute - b.requestMinute);
  }, [remoteFlightId, remoteFlightShipments, selectedFlightShipments, shipments, usesRemoteBatchShipments]);

  const relatedShipmentCounts = useMemo(() => {
    const counts = new Map<string, number>();
    for (const shipment of shipments) {
      for (const flightId of shipment.flightIds) {
        counts.set(flightId, (counts.get(flightId) ?? 0) + 1);
      }
    }
    return counts;
  }, [shipments]);

  const filteredAndSortedFlights = useMemo(() => {
    let result = [...flights];

    if (search.trim()) {
      const query = search.toLowerCase();
      result = result.filter(
        (flight) =>
          flight.id.toLowerCase().includes(query) ||
          flight.origin.toLowerCase().includes(query) ||
          flight.destination.toLowerCase().includes(query)
      );
    }

    if (originFilter !== "Cualquiera") {
      result = result.filter((flight) => flight.origin === originFilter);
    }

    if (destinationFilter !== "Cualquiera") {
      result = result.filter((flight) => flight.destination === destinationFilter);
    }

    if (statusFilter === "Activos") {
      result = result.filter((flight) => activeFlightIds.has(flight.id));
    } else if (statusFilter === "No iniciados") {
      result = result.filter((flight) => !activeFlightIds.has(flight.id));
    }

    if (colorFilter !== "Todos") {
      result = result.filter((flight) => capacityStatus(flight.utilization) === colorFilter);
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
  }, [activeFlightIds, colorFilter, destinationFilter, flights, originFilter, search, sortBy, sortOrder, statusFilter]);

  const totalPages = Math.max(1, Math.ceil(filteredAndSortedFlights.length / PAGE_SIZE_FLIGHTS));
  const currentPage = Math.min(page, totalPages);
  const visibleFlights = useMemo(
    () =>
      filteredAndSortedFlights.slice(
        (currentPage - 1) * PAGE_SIZE_FLIGHTS,
        currentPage * PAGE_SIZE_FLIGHTS
      ),
    [currentPage, filteredAndSortedFlights]
  );
  const canGoBack = currentPage > 1;
  const canGoForward = currentPage < totalPages;

  useEffect(() => {
    if (page > totalPages) setPage(totalPages);
  }, [page, totalPages]);

  useEffect(() => {
    if (!selectedFlightId) {
      lastPagedSelectedFlightId.current = null;
      return;
    }
    if (lastPagedSelectedFlightId.current === selectedFlightId) return;

    lastPagedSelectedFlightId.current = selectedFlightId;
    const selectedIndex = filteredAndSortedFlights.findIndex((flight) => flight.id === selectedFlightId);
    if (selectedIndex >= 0) {
      setPage(Math.floor(selectedIndex / PAGE_SIZE_FLIGHTS) + 1);
    }
  }, [filteredAndSortedFlights, selectedFlightId]);

  useEffect(() => {
    if (!selectedFlightId) return;

    const selectedRow = selectedFlightRowRef.current;
    if (selectedRow) {
      selectedRow.scrollIntoView({ behavior: "smooth", block: "nearest" });
    }
  }, [selectedFlightId, visibleFlights]);

  const flightMomentData = data ?? null;

  if (viewMode === "flight-shipments" && selectedFlightShipments) {
    const isLoadingRelatedShipments =
      usesRemoteBatchShipments &&
      remoteFlightId === selectedFlightShipments.id &&
      remoteFlightShipmentsLoading;
    const relatedShipmentsError =
      usesRemoteBatchShipments && remoteFlightId === selectedFlightShipments.id
        ? remoteFlightShipmentsError
        : "";

    return (
      <div className="flights-table">
        <div style={{ display: "flex", justifyContent: "space-between", alignItems: "center", marginBottom: "1rem" }}>
          <h3 style={{ margin: 0 }}>Envíos del vuelo {selectedFlightShipments.id}</h3>
          <button
            type="button"
            onClick={() => setViewMode("flights")}
            style={{ cursor: "pointer", background: "transparent", border: "1px solid #cbd5e0", borderRadius: 6, padding: "0.4rem 0.8rem" }}
          >
            Volver
          </button>
        </div>
        <div className="table">
          {isLoadingRelatedShipments ? (
            <div className="empty-state">Cargando envíos del vuelo...</div>
          ) : relatedShipmentsError ? (
            <div className="empty-state">{relatedShipmentsError}</div>
          ) : relatedShipments.length === 0 ? (
            <div className="empty-state">No se encontraron envíos relacionados.</div>
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

  return (
    <div className="flights-table">
      <div className="search-bar" style={{ marginBottom: "0.5rem" }}>
        <input
          type="text"
          placeholder="Buscar..."
          value={search}
          onChange={(event) => {
            setSearch(event.target.value);
            setPage(1);
          }}
          style={{ width: "100%" }}
        />
      </div>

      <div className="filters" style={{ display: "flex", gap: "1rem", alignItems: "center", marginBottom: "1rem" }}>
        <label className="text-sm">
          Estado:
          <select
            value={statusFilter}
            onChange={(event) => {
              setStatusFilter(event.target.value as "Todos" | "Activos" | "No iniciados");
              setPage(1);
            }}
          >
            <option value="Todos">Todos</option>
            <option value="Activos">Activos</option>
            <option value="No iniciados">No iniciados</option>
          </select>
        </label>

        <label className="text-sm">
          Origen:
          <select
            value={originFilter}
            onChange={(event) => {
              setOriginFilter(event.target.value);
              setPage(1);
            }}
          >
            <option value="Cualquiera">Cualquiera</option>
            {airportOptions.map((airport) => (
              <option key={airport} value={airport}>
                {airport}
              </option>
            ))}
          </select>
        </label>

        <label className="text-sm">
          Destino:
          <select
            value={destinationFilter}
            onChange={(event) => {
              setDestinationFilter(event.target.value);
              setPage(1);
            }}
          >
            <option value="Cualquiera">Cualquiera</option>
            {airportOptions.map((airport) => (
              <option key={airport} value={airport}>
                {airport}
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
            onChange={(event) => {
              setSortBy(event.target.value as "utilization" | "departureMinute" | "arrivalMinute" | "origin" | "destination");
              setPage(1);
            }}
          >
            <option value="utilization">Ocupación</option>
            <option value="departureMinute">Hora de salida</option>
            <option value="arrivalMinute">Hora de llegada</option>
            <option value="origin">Origen</option>
            <option value="destination">Destino</option>
          </select>
        </label>

        <div className="flex items-center gap-2">
          <label className="text-sm">
            Dirección:
            <select
              value={sortOrder}
              onChange={(event) => {
                setSortOrder(event.target.value as "asc" | "desc");
                setPage(1);
              }}
            >
              <option value="asc">Ascendente</option>
              <option value="desc">Descendente</option>
            </select>
          </label>
          {!hideColorFilter && (
            <label className="text-sm">
              Color:
              <select
                value={colorFilter}
                onChange={(event) => {
                  setColorFilter(event.target.value as ColorFilter);
                  setPage(1);
                }}
              >
                <option value="Todos">Todos</option>
                <option value="green">Verde</option>
                <option value="yellow">Amarillo</option>
                <option value="red">Rojo</option>
                <option value="gray">Gris</option>
              </select>
            </label>
          )}
        </div>
      </div>

      <div className="table">
        {visibleFlights.length === 0 ? (
          <div className="empty-state">No se encontraron resultados.</div>
        ) : (
          visibleFlights.map((flight) => {
            const active = activeFlightIds.has(flight.id);
            const isSelected = selectedFlightId === flight.id;
            const cancellationKey = `${flight.id}@${flight.absoluteDepartureMinute}`;
            const isCancelled =
              cancelledFlightKeys.has(cancellationKey) ||
              cancelledFlightKeys.has(`PENDING@${flight.id}`) ||
              cancelledFlightKeys.has(flight.id);
            const remoteResultMatchesFlight = remoteFlightId === flight.id;
            const isLoadingFlightShipments =
              isSelected &&
              usesRemoteBatchShipments &&
              (!remoteResultMatchesFlight || remoteFlightShipmentsLoading);
            const remoteFlightError =
              isSelected && remoteResultMatchesFlight ? remoteFlightShipmentsError : "";
            const relatedCount =
              isSelected && usesRemoteBatchShipments && remoteResultMatchesFlight
                ? remoteFlightShipments.length
                : relatedShipmentCounts.get(flight.id) ?? 0;
            const status = capacityStatus(flight.utilization);
            const accentColor = STATUS_COLOR[status];

            return (
              <Fragment key={flight.id}>
                <div
                  ref={isSelected ? selectedFlightRowRef : undefined}
                  className={[
                    "row",
                    !active ? "row-inactive" : "",
                    isSelected ? "selected" : "",
                  ].filter(Boolean).join(" ")}
                  onClick={() => onSelectFlight?.(flight.id)}
                  style={{
                    opacity: active ? 1 : 0.6,
                    cursor: onSelectFlight ? "pointer" : undefined,
                    borderColor: isSelected ? "#2563eb" : accentColor,
                    background: isSelected
                      ? `linear-gradient(90deg, ${accentColor}22 0%, #eff6ff 100%)`
                      : `${accentColor}16`,
                    boxShadow: isSelected
                      ? "inset 0 0 0 1px rgba(37, 99, 235, 0.2)"
                      : `inset 0 0 0 1px ${accentColor}30`,
                  }}
                >
                  <span className={`dot ${status}`}></span>
                  <div className="row-main flight-route-main">
                    <div className="flight-route-point">
                      <strong>{flight.origin}</strong>
                      <span>{formatFlightMoment(flightMomentData, flight.absoluteDepartureMinute, displayGmtOffset)}</span>
                    </div>
                    <span className="flight-route-arrow">-&gt;</span>
                    <div className="flight-route-point">
                      <strong>{flight.destination}</strong>
                      <span>{formatFlightMoment(flightMomentData, flight.absoluteArrivalMinute, displayGmtOffset)}</span>
                    </div>
                  </div>
                  <span
                    className="capacity-pill"
                    style={{
                      background: accentColor,
                      display: "inline-flex",
                      alignItems: "center",
                      padding: "0.2rem 0.5rem",
                      minWidth: "auto",
                      whiteSpace: "nowrap",
                    }}
                  >
                    {isCancelled ? "Cancelado" : active ? `${Math.round(flight.utilization * 100)}%` : "No iniciado"}
                  </span>
                </div>
                {isSelected && (
                  <div className="table-action-bar" aria-label={`Acciones del vuelo ${flight.id}`}>
                    <button
                      type="button"
                      className="table-action-button"
                      disabled={isLoadingFlightShipments || Boolean(remoteFlightError) || relatedCount === 0}
                      onClick={() => {
                        setSelectedFlightShipments(flight);
                        setViewMode("flight-shipments");
                      }}
                    >
                      {isLoadingFlightShipments
                        ? "Buscando envíos..."
                        : remoteFlightError
                          ? "No se pudieron cargar"
                          : relatedCount > 0
                            ? `Ver envíos (${relatedCount})`
                            : "Sin envíos"}
                    </button>
                    <button
                      type="button"
                      className="table-action-button table-action-button-ghost"
                      onClick={() => onSelectFlight?.(flight.id)}
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
      {filteredAndSortedFlights.length > 0 && (
        <div className="segmented" style={{ marginTop: "0.75rem", justifyContent: "space-between" }}>
          <button type="button" disabled={!canGoBack} onClick={() => setPage((value) => Math.max(1, value - 1))}>
            Anterior
          </button>
          <span className="text-sm">
            {currentPage}/{totalPages} - {filteredAndSortedFlights.length} vuelos
          </span>
          <button type="button" disabled={!canGoForward} onClick={() => setPage((value) => Math.min(totalPages, value + 1))}>
            Siguiente
          </button>
        </div>
      )}
    </div>
  );
}
