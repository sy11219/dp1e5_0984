import { useState, useMemo } from "react";
import type { Shipment } from "../types";
import { formatSimMinute } from "../utils/formatters";
import { FlightListModal } from "./FlightListModal";

interface ShipmentsTableProps {
  shipments: Shipment[];
  simMinute: number;
}

const ANY = "Cualquiera";

export function ShipmentsTable({ shipments, simMinute }: ShipmentsTableProps) {
  const [search, setSearch] = useState("");
  const [originAirport, setOriginAirport] = useState(ANY);
  const [destinationAirport, setDestinationAirport] = useState(ANY);
  const [selectedShipment, setSelectedShipment] = useState<Shipment | null>(null);

  // Opciones únicas de aeropuertos
  const airportOptions = useMemo(() => {
    const set = new Set<string>();
    for (const s of shipments) {
      set.add(s.origin);
      set.add(s.destination);
    }
    return [...set].sort();
  }, [shipments]);

  const visible = useMemo(() => {
    let result = [...shipments].sort((a, b) => a.requestMinute - b.requestMinute);
    if (search.trim()) {
      const q = search.toLowerCase();
      result = result.filter(s =>
        s.clientId.toLowerCase().includes(q) ||
        s.origin.toLowerCase().includes(q) ||
        s.destination.toLowerCase().includes(q) ||
        s.flightIds.some(id => id.toLowerCase().includes(q))
      );
    }

    if (originAirport !== ANY) result = result.filter(s => s.origin === originAirport);
    if (destinationAirport !== ANY) result = result.filter(s => s.destination === destinationAirport);

    return result.slice(0, 12);
  }, [shipments, search, originAirport, destinationAirport]);

  if (!shipments.length) return <div className="empty-state">No hay envíos registrados.</div>;

  return (
    <div className="shipments-table">
      <div className="search-bar" style={{ marginBottom: "0.5rem" }}>
        <input
          type="text"
          placeholder="Buscar por cliente, origen, destino o vuelo..."
          value={search}
          onChange={e => setSearch(e.target.value)}
          style={{ width: "100%" }}
        />
      </div>

      <div className="filters" style={{ display: "flex", gap: "1rem", marginBottom: "1rem" }}>
        <label>
          Origen:
          <select value={originAirport} onChange={e => setOriginAirport(e.target.value)}>
            <option value={ANY}>{ANY}</option>
            {airportOptions.map(o => <option key={o} value={o}>{o}</option>)}
          </select>
        </label>
        <label>
          Destino:
          <select value={destinationAirport} onChange={e => setDestinationAirport(e.target.value)}>
            <option value={ANY}>{ANY}</option>
            {airportOptions.map(o => <option key={o} value={o}>{o}</option>)}
          </select>
        </label>
      </div>

      <div className="table">
        {visible.length === 0 ? (
          <div className="empty-state">No se encontraron resultados.</div>
        ) : (
          visible.map(s => {
            const status = !s.planned ? "red" : s.onTime ? "green" : "yellow";
            const isCompleted = simMinute >= s.estimatedArrival;
            return (
              <div className="row" key={`${s.id}-${s.requestMinute}`}>
                <span className={`dot ${status}`}></span>
                <div className="row-main">
                  <strong>{s.clientId}</strong>
                  <span>{`${s.origin} → ${s.destination} · ${s.suitcases} maletas`}</span>
                  <span>{`Pedido: ${formatSimMinute(s.requestMinute)} · Llegada: ${formatSimMinute(s.estimatedArrival)}`}</span>
                  <div style={{ display: "flex", gap: "0.5rem", marginTop: "0.25rem" }}>
                    <button
                      onClick={() => setSelectedShipment(s)}
                      style={{ padding: "0.25rem 0.5rem", fontSize: "0.75rem", background: "#4a5568", color: "#fff", border: "none", borderRadius: "4px" }}
                    >
                      Ver vuelos ({s.flightIds.length})
                    </button>
                    <span className="capacity-pill" style={{ background: isCompleted ? "#2f855a" : "#ffbf00", color: "#fff" }}>
                      {isCompleted ? "Entregado" : "En curso"}
                    </span>
                  </div>
                </div>
              </div>
            );
          })
        )}
      </div>

      {selectedShipment && <FlightListModal shipment={selectedShipment} onClose={() => setSelectedShipment(null)} />}
    </div>
  );
}
