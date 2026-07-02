import { useMemo, useState } from "react";
import type { Flight, Shipment, SimulationData } from "../types";
import { formatSimMinute } from "../utils/formatters";
import { FlightListModal } from "./FlightListModal";

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

function getFirstFlightDepartureMinute(
  flightIds: string[],
  requestMinute: number
): number | null {
  if (flightIds.length === 0) return null;

  const parts = flightIds[0].split("-");
  const departureTime = parts[2];
  if (!departureTime || departureTime.length !== 4) return null;

  const hour = Number.parseInt(departureTime.slice(0, 2), 10);
  const min = Number.parseInt(departureTime.slice(2), 10);
  const departureMinute = hour * 60 + min;
  const requestDay = Math.floor(requestMinute / 1440);
  const requestMinuteOfDay = requestMinute % 1440;

  return requestMinuteOfDay <= departureMinute
    ? requestDay * 1440 + departureMinute
    : (requestDay + 1) * 1440 + departureMinute;
}

function getShipmentStatus(
  shipment: Shipment,
  simMinute: number,
  absoluteDepartureMinute: number | null
): string {
  const isCompleted = shipment.planned && simMinute >= shipment.estimatedArrival;
  if (isCompleted) return "Entregado";
  if (absoluteDepartureMinute !== null) {
    return simMinute >= absoluteDepartureMinute ? "En curso" : "Planeado";
  }
  return shipment.planned ? "Planeado" : "Sin ruta";
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
  const [page, setPage] = useState(1);
  const [selectedShipment, setSelectedShipment] = useState<Shipment | null>(null);
  const gmtOffset = displayGmtOffset ?? 0;

  const airportOptions = useMemo(() => {
    const set = new Set<string>();
    for (const shipment of shipments) {
      set.add(shipment.origin);
      set.add(shipment.destination);
    }
    return [...set].sort();
  }, [shipments]);

  const filtered = useMemo(() => {
    let result = [...shipments].sort((a, b) => a.requestMinute - b.requestMinute);

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

    if (originAirport !== ANY) result = result.filter((shipment) => shipment.origin === originAirport);
    if (destinationAirport !== ANY) {
      result = result.filter((shipment) => shipment.destination === destinationAirport);
    }

    if (statusFilter !== ANY) {
      result = result.filter((shipment) => {
        const absoluteDepartureMinute = getFirstFlightDepartureMinute(
          shipment.flightIds,
          shipment.requestMinute
        );
        const shipmentStatus = getShipmentStatus(shipment, simMinute, absoluteDepartureMinute);
        if (statusFilter === "in-progress") return shipmentStatus === "En curso";
        if (statusFilter === "delivered") return shipmentStatus === "Entregado";
        if (statusFilter === "planned") return shipmentStatus === "Planeado";
        if (statusFilter === "unplanned") return !shipment.planned;
        return true;
      });
    }

    return result;
  }, [shipments, search, originAirport, destinationAirport, statusFilter, simMinute]);

  const totalPages = Math.max(1, Math.ceil(filtered.length / PAGE_SIZE));
  const currentPage = Math.min(page, totalPages);
  const paginatedItems = filtered.slice((currentPage - 1) * PAGE_SIZE, currentPage * PAGE_SIZE);
  const canGoBack = currentPage > 1;
  const canGoForward = currentPage < totalPages;

  const handleSelectShipment = (shipment: Shipment) => {
    setSelectedShipment(shipment);
    onSelectShipment?.(shipment);
  };

  if (!shipments.length) return <div className="empty-state">No hay envios registrados.</div>;

  return (
    <div className="shipments-table">
      <div className="search-bar" style={{ marginBottom: "0.5rem" }}>
        <input
          type="text"
          placeholder="Buscar por envio, cliente, origen, destino o vuelo..."
          value={search}
          onChange={(event) => {
            setSearch(event.target.value);
            setPage(1);
          }}
          style={{ width: "100%" }}
        />
      </div>

      <div className="filters" style={{ display: "flex", gap: "0.75rem", marginBottom: "1rem", flexWrap: "wrap" }}>
        <label className="text-sm">
          Origen:
          <select value={originAirport} onChange={(event) => setOriginAirport(event.target.value)}>
            <option value={ANY}>{ANY}</option>
            {airportOptions.map((option) => (
              <option key={option} value={option}>{option}</option>
            ))}
          </select>
        </label>
        <label className="text-sm">
          Destino:
          <select value={destinationAirport} onChange={(event) => setDestinationAirport(event.target.value)}>
            <option value={ANY}>{ANY}</option>
            {airportOptions.map((option) => (
              <option key={option} value={option}>{option}</option>
            ))}
          </select>
        </label>
        <label className="text-sm">
          Estado:
          <select value={statusFilter} onChange={(event) => setStatusFilter(event.target.value)}>
            <option value={ANY}>{ANY}</option>
            <option value="in-progress">En curso</option>
            <option value="delivered">Entregado</option>
            <option value="planned">Planeado</option>
            <option value="unplanned">Sin ruta</option>
          </select>
        </label>
      </div>

      <div className="table">
        {paginatedItems.length === 0 ? (
          <div className="empty-state">No se encontraron resultados.</div>
        ) : (
          paginatedItems.map((shipment) => {
            const status = !shipment.planned ? "red" : shipment.onTime ? "green" : "yellow";
            const absoluteDepartureMinute = getFirstFlightDepartureMinute(
              shipment.flightIds,
              shipment.requestMinute
            );
            const shipmentState = getShipmentStatus(shipment, simMinute, absoluteDepartureMinute);
            const statusColor =
              shipmentState === "Entregado"
                ? "#2f855a"
                : shipmentState === "En curso"
                  ? "#3182ce"
                  : shipmentState === "Planeado"
                    ? "#ffbf00"
                    : "#718096";
            const arrivalLabel = shipment.planned
              ? formatSimMinute(shipment.estimatedArrival, gmtOffset)
              : "pendiente";
            const isSelected = selectedShipmentId === shipment.id;

            return (
              <div className={`row ${isSelected ? "selected" : ""}`} key={`${shipment.id}-${shipment.requestMinute}`}>
                <span className={`dot ${status}`}></span>
                <div className="row-main">
                  <strong>{shipment.id}</strong>
                  <span>{`${shipment.origin} -> ${shipment.destination} - ${shipment.suitcases} maletas`}</span>
                  <span>{`Pedido: ${formatSimMinute(shipment.requestMinute, gmtOffset)} - Llegada: ${arrivalLabel}`}</span>
                  <div style={{ display: "flex", gap: "0.5rem", marginTop: "0.25rem", flexWrap: "wrap" }}>
                    <button
                      type="button"
                      onClick={() => handleSelectShipment(shipment)}
                      style={{ padding: "0.25rem 0.5rem", fontSize: "0.75rem", background: "#4a5568", color: "#fff", border: "none", borderRadius: "4px" }}
                    >
                      Ver ruta ({shipment.flightIds.length})
                    </button>
                    <span className="capacity-pill" style={{ background: statusColor, color: "#fff" }}>
                      {shipmentState}
                    </span>
                  </div>
                </div>
              </div>
            );
          })
        )}
      </div>

      {filtered.length > 0 && (
        <div className="segmented" style={{ marginTop: "0.75rem", justifyContent: "space-between" }}>
          <button type="button" disabled={!canGoBack} onClick={() => setPage((value) => Math.max(1, value - 1))}>
            Anterior
          </button>
          <span className="text-sm">
            {currentPage}/{totalPages} - {filtered.length} envios
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
