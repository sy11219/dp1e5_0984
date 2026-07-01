import { useMemo, useState } from "react";
import type { Shipment } from "../types";
import { formatSimMinute } from "../utils/formatters";
import { FlightListModal } from "./FlightListModal";

interface ShipmentsTableProps {
  shipments: Shipment[];
  simMinute: number;
  displayGmtOffset?: number;
}

const ANY = "Cualquiera";
const PAGE_SIZE = 25;

function getFirstFlightDepartureMinute(flightIds: string[], requestMinute: number): number | null {
  if (flightIds.length === 0) return null;

  const parts = flightIds[0].split("-");
  const departureTime = parts[2];
  if (!departureTime || departureTime.length !== 4) return null;

  const hour = parseInt(departureTime.slice(0, 2), 10);
  const min = parseInt(departureTime.slice(2), 10);
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

  return "Planeado";
}

export function ShipmentsTable({ shipments, simMinute, displayGmtOffset }: ShipmentsTableProps) {
  const [search, setSearch] = useState("");
  const [originAirport, setOriginAirport] = useState(ANY);
  const [destinationAirport, setDestinationAirport] = useState(ANY);
  const [statusFilter, setStatusFilter] = useState(ANY);
  const [page, setPage] = useState(1);
  const [selectedShipment, setSelectedShipment] = useState<Shipment | null>(null);
  const gmtOffset = displayGmtOffset ?? 0;

  const airportOptions = useMemo(() => {
    const set = new Set<string>();
    for (const s of shipments) {
      set.add(s.origin);
      set.add(s.destination);
    }
    return [...set].sort();
  }, [shipments]);

  const filtered = useMemo(() => {
    let result = [...shipments].sort((a, b) => a.requestMinute - b.requestMinute);

    if (search.trim()) {
      const q = search.toLowerCase();
      result = result.filter(
        (s) =>
          s.clientId.toLowerCase().includes(q) ||
          s.origin.toLowerCase().includes(q) ||
          s.destination.toLowerCase().includes(q) ||
          s.flightIds.some((id) => id.toLowerCase().includes(q))
      );
    }

    if (originAirport !== ANY) result = result.filter((s) => s.origin === originAirport);
    if (destinationAirport !== ANY) result = result.filter((s) => s.destination === destinationAirport);

    if (statusFilter !== ANY) {
      result = result.filter((s) => {
        const absoluteDepartureMinute = getFirstFlightDepartureMinute(s.flightIds, s.requestMinute);
        const shipmentStatus = getShipmentStatus(s, simMinute, absoluteDepartureMinute);

        if (statusFilter === "in-progress") return shipmentStatus === "En curso";
        if (statusFilter === "delivered") return shipmentStatus === "Entregado";
        if (statusFilter === "planned") return shipmentStatus === "Planeado";
        if (statusFilter === "unplanned") return !s.planned;

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

  if (!shipments.length) return <div className="empty-state">No hay envios registrados.</div>;

  return (
    <div className="shipments-table">
      <div className="search-bar" style={{ marginBottom: "0.5rem" }}>
        <input
          type="text"
          placeholder="Buscar por cliente, origen, destino o vuelo..."
          value={search}
          onChange={(e) => setSearch(e.target.value)}
          style={{ width: "100%" }}
        />
      </div>

      <div className="filters" style={{ display: "flex", gap: "0.75rem", marginBottom: "1rem", flexWrap: "wrap" }}>
        <label className="text-sm">
          Origen:
          <select value={originAirport} onChange={(e) => setOriginAirport(e.target.value)}>
            <option value={ANY}>{ANY}</option>
            {airportOptions.map((o) => (
              <option key={o} value={o}>
                {o}
              </option>
            ))}
          </select>
        </label>
        <label className="text-sm">
          Destino:
          <select value={destinationAirport} onChange={(e) => setDestinationAirport(e.target.value)}>
            <option value={ANY}>{ANY}</option>
            {airportOptions.map((o) => (
              <option key={o} value={o}>
                {o}
              </option>
            ))}
          </select>
        </label>
        <label className="text-sm">
          Estado:
          <select value={statusFilter} onChange={(e) => setStatusFilter(e.target.value)}>
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
          paginatedItems.map((s) => {
            const status = !s.planned ? "red" : s.onTime ? "green" : "yellow";
            const absoluteDepartureMinute = getFirstFlightDepartureMinute(s.flightIds, s.requestMinute);
            const shipmentState = getShipmentStatus(s, simMinute, absoluteDepartureMinute);
            const statusColor =
              shipmentState === "Entregado"
                ? "#2f855a"
                : shipmentState === "En curso"
                  ? "#3182ce"
                  : shipmentState === "Planeado"
                    ? "#ffbf00"
                    : "#718096";
            const arrivalLabel = s.planned ? formatSimMinute(s.estimatedArrival, gmtOffset) : "pendiente";

            return (
              <div className="row" key={`${s.id}-${s.requestMinute}`}>
                <span className={`dot ${status}`}></span>
                <div className="row-main">
                  <strong>{s.id}</strong>
                  <span>{`${s.origin} -> ${s.destination} · ${s.suitcases} maletas`}</span>
                  <span>{`Pedido: ${formatSimMinute(s.requestMinute, gmtOffset)} · Llegada: ${arrivalLabel}`}</span>
                  <div style={{ display: "flex", gap: "0.5rem", marginTop: "0.25rem", flexWrap: "wrap" }}>
                    <button
                      onClick={() => setSelectedShipment(s)}
                      style={{ padding: "0.25rem 0.5rem", fontSize: "0.75rem", background: "#4a5568", color: "#fff", border: "none", borderRadius: "4px" }}
                    >
                      Ver vuelos ({s.flightIds.length})
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
          <button type="button" disabled={!canGoBack} onClick={() => setPage((p) => Math.max(1, p - 1))}>
            Anterior
          </button>
          <span className="text-sm">
            {currentPage}/{totalPages} · {filtered.length} envios
          </span>
          <button type="button" disabled={!canGoForward} onClick={() => setPage((p) => p + 1)}>
            Siguiente
          </button>
        </div>
      )}

      {selectedShipment && <FlightListModal shipment={selectedShipment} onClose={() => setSelectedShipment(null)} />}
    </div>
  );
}
