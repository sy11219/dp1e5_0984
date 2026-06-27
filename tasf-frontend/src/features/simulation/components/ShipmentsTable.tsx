import { useEffect, useMemo, useState } from "react";
import { getBatchShipmentsPageRequest } from "../../../api/simulationApi";
import type { Shipment, ShipmentPage } from "../types";
import { formatSimMinute } from "../utils/formatters";
import { FlightListModal } from "./FlightListModal";

interface ShipmentsTableProps {
  shipments: Shipment[];
  simMinute: number;
  simulationId?: string;
  refreshKey?: string | number;
  airportOptions?: string[];
}

const ANY = "Cualquiera";
const PAGE_SIZE = 25;

export function ShipmentsTable({
  shipments,
  simMinute,
  simulationId,
  refreshKey,
  airportOptions: airportOptionsProp,
}: ShipmentsTableProps) {
  const [search, setSearch] = useState("");
  const [originAirport, setOriginAirport] = useState(ANY);
  const [destinationAirport, setDestinationAirport] = useState(ANY);
  const [statusFilter, setStatusFilter] = useState(ANY);
  const [page, setPage] = useState(1);
  const [remotePage, setRemotePage] = useState<ShipmentPage | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState("");
  const [selectedShipment, setSelectedShipment] = useState<Shipment | null>(null);

  const airportOptions = useMemo(() => {
    if (airportOptionsProp?.length) return [...airportOptionsProp].sort();
    const set = new Set<string>();
    for (const s of shipments) {
      set.add(s.origin);
      set.add(s.destination);
    }
    return [...set].sort();
  }, [airportOptionsProp, shipments]);

  useEffect(() => {
    setPage(1);
  }, [search, originAirport, destinationAirport, statusFilter, simulationId]);

  useEffect(() => {
    if (!simulationId) {
      setRemotePage(null);
      return;
    }

    let cancelled = false;
    setLoading(true);
    setError("");

    void getBatchShipmentsPageRequest(simulationId, {
      page,
      pageSize: PAGE_SIZE,
      search,
      origin: originAirport === ANY ? "" : originAirport,
      destination: destinationAirport === ANY ? "" : destinationAirport,
      status: statusFilter === ANY ? "" : statusFilter,
    })
      .then((payload) => {
        if (!cancelled) setRemotePage(payload);
      })
      .catch((err) => {
        if (!cancelled) {
          setError(err instanceof Error ? err.message : "No se pudieron cargar los envios.");
        }
      })
      .finally(() => {
        if (!cancelled) setLoading(false);
      });

    return () => {
      cancelled = true;
    };
  }, [destinationAirport, originAirport, page, refreshKey, search, simulationId, statusFilter]);

  const localFiltered = useMemo(() => {
    let result = [...shipments].sort((a, b) => a.requestMinute - b.requestMinute);
    if (search.trim()) {
      const q = search.toLowerCase();
      result = result.filter((s) =>
        s.id.toLowerCase().includes(q) ||
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
        if (statusFilter === "planned") return s.planned;
        if (statusFilter === "unplanned") return !s.planned;
        if (statusFilter === "ontime") return s.planned && s.onTime;
        if (statusFilter === "late") return s.planned && !s.onTime;
        if (statusFilter === "delivered") return s.planned && simMinute >= s.estimatedArrival;
        if (statusFilter === "pending") return !s.planned || simMinute < s.estimatedArrival;
        return true;
      });
    }
    return result;
  }, [destinationAirport, originAirport, search, shipments, simMinute, statusFilter]);

  const localTotalPages = Math.max(1, Math.ceil(localFiltered.length / PAGE_SIZE));
  const localPage = Math.min(page, localTotalPages);
  const localItems = localFiltered.slice((localPage - 1) * PAGE_SIZE, localPage * PAGE_SIZE);

  const items = simulationId ? (remotePage?.items ?? []) : localItems;
  const total = simulationId ? (remotePage?.total ?? 0) : localFiltered.length;
  const totalPages = simulationId ? (remotePage?.totalPages ?? 1) : localTotalPages;
  const currentPage = simulationId ? (remotePage?.page ?? page) : localPage;

  const canGoBack = currentPage > 1;
  const canGoForward = currentPage < totalPages;

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
            {airportOptions.map((o) => <option key={o} value={o}>{o}</option>)}
          </select>
        </label>
        <label className="text-sm">
          Destino:
          <select value={destinationAirport} onChange={(e) => setDestinationAirport(e.target.value)}>
            <option value={ANY}>{ANY}</option>
            {airportOptions.map((o) => <option key={o} value={o}>{o}</option>)}
          </select>
        </label>
        <label className="text-sm">
          Estado:
          <select value={statusFilter} onChange={(e) => setStatusFilter(e.target.value)}>
            <option value={ANY}>{ANY}</option>
            <option value="planned">Planificado</option>
            <option value="unplanned">Sin ruta</option>
            <option value="ontime">A tiempo</option>
            <option value="late">Tarde</option>
            <option value="delivered">Entregado</option>
            <option value="pending">Pendiente</option>
          </select>
        </label>
      </div>

      <div className="table">
        {loading ? (
          <div className="empty-state">Cargando envios...</div>
        ) : error ? (
          <div className="empty-state">{error}</div>
        ) : total === 0 ? (
          <div className="empty-state">No se encontraron resultados.</div>
        ) : (
          items.map((s) => {
            const status = !s.planned ? "red" : s.onTime ? "green" : "yellow";
            const isCompleted = s.planned && simMinute >= s.estimatedArrival;
            const arrivalLabel = s.planned ? formatSimMinute(s.estimatedArrival) : "pendiente";
            const progressLabel = !s.planned ? "Sin ruta" : isCompleted ? "Entregado" : "En curso";
            return (
              <div className="row" key={`${s.id}-${s.requestMinute}`}>
                <span className={`dot ${status}`}></span>
                <div className="row-main">
                  <strong>{s.clientId}</strong>
                  <span>{`${s.origin} -> ${s.destination} | ${s.suitcases} maletas`}</span>
                  <span>{`Pedido: ${formatSimMinute(s.requestMinute)} | Llegada: ${arrivalLabel}`}</span>
                  <div style={{ display: "flex", gap: "0.5rem", marginTop: "0.25rem", flexWrap: "wrap" }}>
                    <button
                      onClick={() => setSelectedShipment(s)}
                      style={{ padding: "0.25rem 0.5rem", fontSize: "0.75rem", background: "#4a5568", color: "#fff", border: "none", borderRadius: "4px" }}
                    >
                      Ver vuelos ({s.flightIds.length})
                    </button>
                    <span className="capacity-pill" style={{ background: isCompleted ? "#2f855a" : "#ffbf00", color: "#fff" }}>
                      {progressLabel}
                    </span>
                  </div>
                </div>
              </div>
            );
          })
        )}
      </div>

      {total > 0 && (
        <div className="segmented" style={{ marginTop: "0.75rem", justifyContent: "space-between" }}>
          <button type="button" disabled={!canGoBack || loading} onClick={() => setPage((p) => Math.max(1, p - 1))}>
            Anterior
          </button>
          <span className="text-sm">
            {currentPage}/{totalPages} | {total} envios
          </span>
          <button type="button" disabled={!canGoForward || loading} onClick={() => setPage((p) => p + 1)}>
            Siguiente
          </button>
        </div>
      )}

      {selectedShipment && <FlightListModal shipment={selectedShipment} onClose={() => setSelectedShipment(null)} />}
    </div>
  );
}
