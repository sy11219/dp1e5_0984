import { Fragment, useEffect, useMemo, useState } from "react";
import { getBatchShipmentsPageRequest } from "../../../../../api/simulationApi";
import type { Flight, Shipment, SimulationData } from "../../../types";
import { formatFlightMoment } from "../../../utils/formatters";
import { FlightListModal } from "../FlightListModal";

interface ShipmentsTableProps {
  shipments: Shipment[];
  flights: Flight[];
  simMinute: number;
  data?: SimulationData | null;
  displayGmtOffset?: number;
  selectedShipmentId?: string | null;
  onSelectShipment?: (shipment: Shipment) => void;
  onSelectFlight?: (id: string) => void;
}

const ANY = "Cualquiera";
const PAGE_SIZE = 25;

type ShipmentState = "Entregado" | "En curso" | "Planeado" | "Sin ruta";
type ShipmentSortBy = "delivery" | "registration" | "departure";
type SortOrder = "asc" | "desc";

function getShipmentStatus(
  shipment: Shipment,
  simMinute: number,
  flightById: Map<string, Flight>
): ShipmentState {
  if (!shipment.planned) return "Sin ruta";

  const routeFlights = shipment.flightIds
    .map((id) => flightById.get(id))
    .filter((flight): flight is Flight => Boolean(flight));

  if (
    routeFlights.some(
      (flight) =>
        simMinute >= flight.absoluteDepartureMinute &&
        simMinute <= flight.absoluteArrivalMinute
    )
  ) {
    return "En curso";
  }

  if (shipment.estimatedArrival > 0 && simMinute >= shipment.estimatedArrival) return "Entregado";
  return "Planeado";
}

function matchesAirportFilters(
  shipment: Shipment,
  originAirport: string,
  destinationAirport: string,
  flightById: Map<string, Flight>
): boolean {
  if (originAirport === ANY && destinationAirport === ANY) return true;

  const matches = (origin: string, destination: string) =>
    (originAirport === ANY || origin === originAirport) &&
    (destinationAirport === ANY || destination === destinationAirport);

  if (matches(shipment.origin, shipment.destination)) return true;

  return shipment.flightIds.some((id) => {
    const flight = flightById.get(id);
    return Boolean(flight && matches(flight.origin, flight.destination));
  });
}

function compareShipmentsByNextDelivery(a: Shipment, b: Shipment, simMinute: number): number {
  const aDelivered = a.planned && a.estimatedArrival > 0 && simMinute >= a.estimatedArrival;
  const bDelivered = b.planned && b.estimatedArrival > 0 && simMinute >= b.estimatedArrival;
  const aUpcoming = a.planned && a.estimatedArrival > 0 && !aDelivered;
  const bUpcoming = b.planned && b.estimatedArrival > 0 && !bDelivered;

  const bucket = (upcoming: boolean, delivered: boolean) => {
    if (upcoming) return 0;
    if (!delivered) return 1;
    return 2;
  };

  const bucketDiff = bucket(aUpcoming, aDelivered) - bucket(bUpcoming, bDelivered);
  if (bucketDiff !== 0) return bucketDiff;
  if (aUpcoming && bUpcoming) return a.estimatedArrival - b.estimatedArrival;
  if (aDelivered && bDelivered) return b.estimatedArrival - a.estimatedArrival;
  return a.requestMinute - b.requestMinute || a.id.localeCompare(b.id);
}

function firstDepartureMinute(shipment: Shipment, flightById: Map<string, Flight>): number {
  if (shipment.flightLegs?.length) {
    return Math.min(...shipment.flightLegs.map((leg) => leg.absoluteDepartureMinute));
  }

  const departures = shipment.flightIds
    .map((id) => flightById.get(id)?.absoluteDepartureMinute)
    .filter((minute): minute is number => typeof minute === "number");
  return departures.length ? Math.min(...departures) : Number.POSITIVE_INFINITY;
}

function compareShipments(
  a: Shipment,
  b: Shipment,
  simMinute: number,
  flightById: Map<string, Flight>,
  sortBy: ShipmentSortBy,
  sortOrder: SortOrder
): number {
  if (sortBy === "delivery") return compareShipmentsByNextDelivery(a, b, simMinute);

  const direction = sortOrder === "asc" ? 1 : -1;
  const aValue = sortBy === "registration" ? a.requestMinute : firstDepartureMinute(a, flightById);
  const bValue = sortBy === "registration" ? b.requestMinute : firstDepartureMinute(b, flightById);

  if (aValue !== bValue) return (aValue - bValue) * direction;
  return a.id.localeCompare(b.id);
}

export function ShipmentsTable({
  shipments,
  flights,
  simMinute,
  data,
  displayGmtOffset,
  selectedShipmentId,
  onSelectShipment,
  onSelectFlight,
}: ShipmentsTableProps) {
  const [search, setSearch] = useState("");
  const [originAirport, setOriginAirport] = useState(ANY);
  const [destinationAirport, setDestinationAirport] = useState(ANY);
  const [statusFilter, setStatusFilter] = useState(ANY);
  const [shipmentHistoryHours, setShipmentHistoryHours] = useState(1);
  const [departureWithinHours, setDepartureWithinHours] = useState("");
  const [sortBy, setSortBy] = useState<ShipmentSortBy>("delivery");
  const [sortOrder, setSortOrder] = useState<SortOrder>("asc");
  const [page, setPage] = useState(1);
  const [selectedShipment, setSelectedShipment] = useState<Shipment | null>(null);
  const [localSelectedShipmentId, setLocalSelectedShipmentId] = useState<string | null>(null);
  const [remoteShipments, setRemoteShipments] = useState<Shipment[]>([]);
  const [remoteTotal, setRemoteTotal] = useState(0);
  const [remoteTotalPages, setRemoteTotalPages] = useState(1);
  const [remoteLoading, setRemoteLoading] = useState(false);
  const [remoteError, setRemoteError] = useState("");
  const gmtOffset = displayGmtOffset;
  const simulationId = data?.simulationId;
  const useRemoteShipments = Boolean(simulationId) && (data?.planningWindowMinutes ?? 0) > 2;
  const isSelectionControlled = selectedShipmentId !== undefined;
  const effectiveSelectedShipmentId = isSelectionControlled ? selectedShipmentId : localSelectedShipmentId;
  const remoteSortMinute = Math.floor(simMinute / 10) * 10;
  const flightById = useMemo(
    () => new Map(flights.map((flight) => [flight.id, flight])),
    [flights]
  );

  const airportOptions = useMemo(() => {
    const set = new Set<string>();
    const source = useRemoteShipments ? remoteShipments : shipments;
    for (const shipment of source) {
      set.add(shipment.origin);
      set.add(shipment.destination);
    }
    for (const flight of flights) {
      set.add(flight.origin);
      set.add(flight.destination);
    }
    return [...set].sort((a, b) => a.localeCompare(b, undefined, { sensitivity: "base" }));
  }, [flights, remoteShipments, shipments, useRemoteShipments]);

  const filtered = useMemo(() => {
    const historyMinutes = shipmentHistoryHours * 60;
    
    let result = shipments.filter(
      (shipment) =>
        shipment.requestMinute <= simMinute &&
        (!shipment.planned || simMinute <= shipment.estimatedArrival + historyMinutes)
    );

    result = result.sort((a, b) => compareShipments(a, b, simMinute, flightById, sortBy, sortOrder));

    if (search.trim()) {
      const query = search.toLowerCase();
      result = result.filter(
        (shipment) =>
          shipment.id.toLowerCase().includes(query) ||
          shipment.clientId.toLowerCase().includes(query) ||
          shipment.origin.toLowerCase().includes(query) ||
          shipment.destination.toLowerCase().includes(query) ||
          shipment.flightIds.some((id) => id.toLowerCase().includes(query))
      );
    }

    if (originAirport !== ANY || destinationAirport !== ANY) {
      result = result.filter((shipment) =>
        matchesAirportFilters(shipment, originAirport, destinationAirport, flightById)
      );
    }

    if (statusFilter !== ANY) {
      result = result.filter((shipment) => {
        const shipmentStatus = getShipmentStatus(shipment, simMinute, flightById);
        if (statusFilter === "in-progress") return shipmentStatus === "En curso";
        if (statusFilter === "delivered") return shipmentStatus === "Entregado";
        if (statusFilter === "planned") return shipmentStatus === "Planeado";
        if (statusFilter === "unplanned") return !shipment.planned;
        return true;
      });
    }

    const departureHours = Number(departureWithinHours);
    if (statusFilter === "planned" && Number.isFinite(departureHours) && departureWithinHours !== "") {
      const maxDepartureMinute = simMinute + Math.max(0, departureHours) * 60;
      result = result.filter((shipment) => {
        const departureMinute = firstDepartureMinute(shipment, flightById);
        return departureMinute >= simMinute && departureMinute <= maxDepartureMinute;
      });
    }

    return result;
  }, [shipments, search, originAirport, destinationAirport, statusFilter, simMinute, flightById, shipmentHistoryHours, departureWithinHours, sortBy, sortOrder]);

  useEffect(() => {
    if (statusFilter !== "planned" && departureWithinHours) {
      setDepartureWithinHours("");
      setPage(1);
    }
  }, [departureWithinHours, statusFilter]);

  useEffect(() => {
    if (!useRemoteShipments || !simulationId) {
      setRemoteShipments([]);
      setRemoteTotal(0);
      setRemoteTotalPages(1);
      setRemoteError("");
      setRemoteLoading(false);
      return;
    }

    let cancelled = false;
    setRemoteLoading(true);
    setRemoteError("");

    void getBatchShipmentsPageRequest(simulationId, {
      page,
      pageSize: PAGE_SIZE,
      currentMinute: remoteSortMinute,
      search,
      origin: originAirport !== ANY ? originAirport : undefined,
      destination: destinationAirport !== ANY ? destinationAirport : undefined,
      status: statusFilter !== ANY ? statusFilter : undefined,
      historyMinutes: (shipmentHistoryHours ?? 0) * 60,
      departureWithinMinutes: statusFilter === "planned" && departureWithinHours !== ""
        ? Math.max(0, Number(departureWithinHours)) * 60
        : undefined,
      sortBy,
      sortOrder,
    }, data?.scenario)
      .then((response) => {
        if (cancelled) return;
        setRemoteShipments(response.items);
        setRemoteTotal(response.total);
        setRemoteTotalPages(Math.max(1, response.totalPages));
        if (response.page !== page) setPage(response.page);
      })
      .catch((error) => {
        if (cancelled) return;
        setRemoteShipments([]);
        setRemoteTotal(0);
        setRemoteTotalPages(1);
        setRemoteError(error instanceof Error ? error.message : "No se pudieron cargar los envíos.");
      })
      .finally(() => {
        if (!cancelled) setRemoteLoading(false);
      });

    return () => {
      cancelled = true;
    };
  }, [data?.scenario, departureWithinHours, destinationAirport, originAirport, page, remoteSortMinute, search, shipmentHistoryHours, simulationId, sortBy, sortOrder, statusFilter, useRemoteShipments]);

  const totalPages = useRemoteShipments
    ? remoteTotalPages
    : Math.max(1, Math.ceil(filtered.length / PAGE_SIZE));
  const currentPage = Math.min(page, totalPages);
  const paginatedItems = useRemoteShipments
    ? remoteShipments
    : filtered.slice((currentPage - 1) * PAGE_SIZE, currentPage * PAGE_SIZE);
  const totalItems = useRemoteShipments ? remoteTotal : filtered.length;
  const canGoBack = currentPage > 1;
  const canGoForward = currentPage < totalPages;
  
  const hasActiveFilters =
    Boolean(search.trim()) ||
    originAirport !== ANY ||
    destinationAirport !== ANY ||
    statusFilter !== ANY ||
    shipmentHistoryHours !== 1 ||
    departureWithinHours !== "" ||
    sortBy !== "delivery" ||
    (sortBy !== "delivery" && sortOrder !== "asc");

  const toggleSelectShipment = (shipment: Shipment) => {
    const nextSelected = effectiveSelectedShipmentId === shipment.id ? null : shipment.id;
    if (!isSelectionControlled) setLocalSelectedShipmentId(nextSelected);
    onSelectShipment?.(shipment);
  };

  const openShipmentRoute = (shipment: Shipment) => {
    if (!isSelectionControlled) setLocalSelectedShipmentId(shipment.id);
    if (effectiveSelectedShipmentId !== shipment.id) onSelectShipment?.(shipment);
    setSelectedShipment(shipment);
  };

  const clearSelectedShipment = (shipment: Shipment) => {
    if (!isSelectionControlled) setLocalSelectedShipmentId(null);
    setSelectedShipment(null);
    onSelectShipment?.(shipment);
  };

  const clearFilters = () => {
    setSearch("");
    setOriginAirport(ANY);
    setDestinationAirport(ANY);
    setStatusFilter(ANY);
    setShipmentHistoryHours(1);
    setDepartureWithinHours("");
    setSortBy("delivery");
    setSortOrder("asc");
    setPage(1);
  };

  return (
    <div className="shipments-table">
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

      <div style={{ display: "flex", alignItems: "center", justifyContent: "space-between", gap: "1rem", flexWrap: "wrap", marginBottom: "1rem" }}>
        <label className="text-sm" style={{ display: "flex", alignItems: "center", gap: "0.5rem", margin: 0 }}>
          Mostrar finalizados hace
          <input
            type="number"
            min={0}
            max={24}
            step={1}
            value={shipmentHistoryHours}
            onChange={(e) => {
              setShipmentHistoryHours(Number(e.target.value));
              setPage(1);
            }}
            style={{ width: "2rem" }}
          />
          h
        </label>
      </div>

      <div className="filters" style={{ display: "flex", gap: "0.75rem", marginBottom: "1rem", flexWrap: "wrap" }}>
        <label className="text-sm">
          Origen:
          <select
            value={originAirport}
            onChange={(event) => {
              setOriginAirport(event.target.value);
              setPage(1);
            }}
          >
            <option value={ANY}>{ANY}</option>
            {airportOptions.map((option) => (
              <option key={option} value={option}>{option}</option>
            ))}
          </select>
        </label>
        <label className="text-sm">
          Destino:
          <select
            value={destinationAirport}
            onChange={(event) => {
              setDestinationAirport(event.target.value);
              setPage(1);
            }}
          >
            <option value={ANY}>{ANY}</option>
            {airportOptions.map((option) => (
              <option key={option} value={option}>{option}</option>
            ))}
          </select>
        </label>
        <label className="text-sm">
          Estado:
          <select
            value={statusFilter}
            onChange={(event) => {
              setStatusFilter(event.target.value);
              if (event.target.value !== "planned") setDepartureWithinHours("");
              setPage(1);
            }}
          >
            <option value={ANY}>{ANY}</option>
            <option value="in-progress">En curso</option>
            <option value="delivered">Entregado</option>
            <option value="planned">Planeado</option>
            <option value="unplanned">Sin ruta</option>
          </select>
        </label>
        <label className="text-sm" style={{ display: "flex", alignItems: "center", gap: "0.35rem" }}>
          Sale en hasta
          <input
            type="number"
            min={0}
            max={240}
            step={1}
            value={departureWithinHours}
            onChange={(event) => {
              setDepartureWithinHours(event.target.value);
              setPage(1);
            }}
            disabled={statusFilter !== "planned"}
            style={{ width: "3.5rem" }}
          />
          h
        </label>
        <label className="text-sm">
          Ordenar por:
          <select
            value={sortBy}
            onChange={(event) => {
              setSortBy(event.target.value as ShipmentSortBy);
              setPage(1);
            }}
          >
            <option value="delivery">Entrega</option>
            <option value="registration">Registro</option>
            <option value="departure">Salida</option>
          </select>
        </label>
        <label className="text-sm">
          Dirección:
          <select
            value={sortOrder}
            onChange={(event) => {
              setSortOrder(event.target.value as SortOrder);
              setPage(1);
            }}
            disabled={sortBy === "delivery"}
          >
            <option value="asc">Ascendente</option>
            <option value="desc">Descendente</option>
          </select>
        </label>
        {hasActiveFilters && (
          <button type="button" onClick={clearFilters}>
            Limpiar filtros
          </button>
        )}
      </div>

      <div className="table">
        {remoteLoading ? (
          <div className="empty-state">Cargando envíos...</div>
        ) : remoteError ? (
          <div className="empty-state">{remoteError}</div>
        ) : paginatedItems.length === 0 ? (
          <div className="empty-state">
            {hasActiveFilters
              ? "No se encontraron resultados con los filtros actuales."
              : "No hay envíos procesados por la planificación todavía."}
          </div>
        ) : (
          paginatedItems.map((shipment) => {
            const status = !shipment.planned ? "red" : shipment.onTime ? "green" : "yellow";
            const shipmentState = getShipmentStatus(shipment, simMinute, flightById);
            const statusColor =
              shipmentState === "Entregado"
                ? "#2f855a"
                : shipmentState === "En curso"
                  ? "#3182ce"
                  : shipmentState === "Planeado"
                    ? "#ffbf00"
                    : "#718096";
            const arrivalLabel = shipment.planned
              ? formatFlightMoment(data, shipment.estimatedArrival, gmtOffset)
              : "pendiente";
            const isSelected = effectiveSelectedShipmentId === shipment.id;

            return (
              <Fragment key={`${shipment.id}-${shipment.requestMinute}`}>
                <div
                  className={`row ${isSelected ? "selected" : ""}`}
                  onClick={() => toggleSelectShipment(shipment)}
                  style={{ cursor: "pointer" }}
                >
                  <span className={`dot ${status}`}></span>
                  <div className="row-main">
                    <strong>{shipment.id}</strong>
                    <span>{`${shipment.origin} -> ${shipment.destination} - ${shipment.suitcases} maletas`}</span>
                    <span>{`Pedido: ${formatFlightMoment(data, shipment.requestMinute, gmtOffset)}`}</span>
                    <span>{`Llegada: ${arrivalLabel}`}</span>
                  </div>
                  <span className="capacity-pill" style={{ background: statusColor, color: "#fff" }}>
                    {shipmentState}
                  </span>
                </div>
                {isSelected && (
                  <div className="table-action-bar" aria-label={`Acciones del envío ${shipment.id}`}>
                    <button
                      type="button"
                      className="table-action-button"
                      onClick={() => openShipmentRoute(shipment)}
                    >
                      Ver ruta
                    </button>
                    <button
                      type="button"
                      className="table-action-button table-action-button-ghost"
                      onClick={() => clearSelectedShipment(shipment)}
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

      {totalItems > 0 && (
        <div className="segmented" style={{ marginTop: "0.75rem", justifyContent: "space-between" }}>
          <button type="button" disabled={!canGoBack} onClick={() => setPage((value) => Math.max(1, value - 1))}>
            Anterior
          </button>
          <span className="text-sm">
            {currentPage}/{totalPages} - {totalItems} envíos
          </span>
          <button type="button" disabled={!canGoForward} onClick={() => setPage((value) => value + 1)}>
            Siguiente
          </button>
        </div>
      )}

      {selectedShipment && (
        <FlightListModal
          shipment={selectedShipment}
          flights={flights}
          data={data}
          onClose={() => setSelectedShipment(null)}
          onSelectFlight={onSelectFlight}
        />
      )}
    </div>
  );
}
