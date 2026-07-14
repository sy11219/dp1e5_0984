import { Fragment, useEffect, useMemo, useState } from "react";
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
  const [statusFilter, setStatusFilter] = useState<"Todos" | "Activos" | "Inactivos">("Todos");
  const [localColorFilter, setLocalColorFilter] = useState<ColorFilter>("Todos");
  const [sortBy, setSortBy] = useState<
    "utilization" | "departureMinute" | "arrivalMinute" | "origin" | "destination"
  >("utilization");
  const [sortOrder, setSortOrder] = useState<"asc" | "desc">("desc");
  const [selectedFlightShipments, setSelectedFlightShipments] = useState<Flight | null>(null);
  const [viewMode, setViewMode] = useState<"flights" | "flight-shipments">("flights");
  const [remoteFlightId, setRemoteFlightId] = useState<string | null>(null);
  const [remoteFlightShipments, setRemoteFlightShipments] = useState<Shipment[]>([]);
  const [remoteFlightShipmentsLoading, setRemoteFlightShipmentsLoading] = useState(false);
  const [remoteFlightShipmentsError, setRemoteFlightShipmentsError] = useState("");
  const colorFilter = colorFilterProp ?? localColorFilter;
  const setColorFilter = onColorFilterChange ?? setLocalColorFilter;
  const batchSimulationId = data?.simulationId;
  const usesRemoteBatchShipments = Boolean(
    batchSimulationId && (data?.planningWindowMinutes ?? 0) > 2
  );

  const origins = useMemo(() => Array.from(new Set(flights.map((flight) => flight.origin))), [flights]);
  const destinations = useMemo(
    () => Array.from(new Set(flights.map((flight) => flight.destination))),
    [flights]
  );

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
        });
        const remainingPages = await Promise.all(
          Array.from({ length: Math.max(0, firstPage.totalPages - 1) }, (_, index) =>
            getBatchShipmentsPageRequest(batchSimulationId, {
              page: index + 2,
              pageSize,
              search: flightId,
            })
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
  }, [batchSimulationId, selectedFlight, usesRemoteBatchShipments]);

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
    } else if (statusFilter === "Inactivos") {
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

  const visibleFlights = useMemo(() => {
    const base = filteredAndSortedFlights.slice(0, 10);
    if (!selectedFlightId || base.some((flight) => flight.id === selectedFlightId)) {
      return base;
    }

    const selected = filteredAndSortedFlights.find((flight) => flight.id === selectedFlightId);
    return selected ? [selected, ...base.slice(0, 9)] : base;
  }, [filteredAndSortedFlights, selectedFlightId]);
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
          onChange={(event) => setSearch(event.target.value)}
          style={{ width: "100%" }}
        />
      </div>

      <div className="filters" style={{ display: "flex", gap: "1rem", alignItems: "center", marginBottom: "1rem" }}>
        <label className="text-sm">
          Estado:
          <select value={statusFilter} onChange={(event) => setStatusFilter(event.target.value as "Todos" | "Activos" | "Inactivos")}>
            <option value="Todos">Todos</option>
            <option value="Activos">Activos</option>
            <option value="Inactivos">Inactivos</option>
          </select>
        </label>

        <label className="text-sm">
          Origen:
          <select value={originFilter} onChange={(event) => setOriginFilter(event.target.value)}>
            <option value="Cualquiera">Cualquiera</option>
            {origins.map((origin) => (
              <option key={origin} value={origin}>
                {origin}
              </option>
            ))}
          </select>
        </label>

        <label className="text-sm">
          Destino:
          <select value={destinationFilter} onChange={(event) => setDestinationFilter(event.target.value)}>
            <option value="Cualquiera">Cualquiera</option>
            {destinations.map((destination) => (
              <option key={destination} value={destination}>
                {destination}
              </option>
            ))}
          </select>
        </label>
      </div>

      <div className="filters" style={{ display: "flex", gap: "1rem", alignItems: "center", marginBottom: "1rem" }}>
        <label className="text-sm">
          Ordenar por:
          <select value={sortBy} onChange={(event) => setSortBy(event.target.value as "utilization" | "departureMinute" | "arrivalMinute" | "origin" | "destination")}>
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
            <select value={sortOrder} onChange={(event) => setSortOrder(event.target.value as "asc" | "desc")}>
              <option value="asc">Ascendente</option>
              <option value="desc">Descendente</option>
            </select>
          </label>
          {!hideColorFilter && (
            <label className="text-sm">
              Color:
              <select value={colorFilter} onChange={(event) => setColorFilter(event.target.value as ColorFilter)}>
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
                    {active ? `${Math.round(flight.utilization * 100)}%` : "Inactivo"}
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
    </div>
  );
}
