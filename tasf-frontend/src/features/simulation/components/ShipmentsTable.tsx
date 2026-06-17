import { useState, useMemo } from "react";
import type { Shipment } from "../types";
import { formatSimMinute } from "../utils/formatters";
import { FlightListModal } from "./FlightListModal";

interface ShipmentsTableProps {
  shipments: Shipment[];
  simMinute: number;
}

export function ShipmentsTable({ shipments, simMinute }: ShipmentsTableProps) {
  const [search, setSearch] = useState("");
  const [selectedShipment, setSelectedShipment] = useState<Shipment | null>(null);

  const visible = useMemo(() => {
    let result = [...shipments].sort((a, b) => a.requestMinute - b.requestMinute);

    if (search.trim()) {
      const query = search.toLowerCase();
      result = result.filter(
        (s) =>
          s.clientId.toLowerCase().includes(query) ||
          s.origin.toLowerCase().includes(query) ||
          s.destination.toLowerCase().includes(query) ||
          s.flightIds.some((id) => id.toLowerCase().includes(query))
      );
    }

    return result.slice(0, 20);
  }, [shipments, search]);

  if (!shipments.length) {
    return <div className="empty-state">No hay envíos registrados.</div>;
  }

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

      <div className="table">
        {visible.length === 0 ? (
          <div className="empty-state">No se encontraron resultados.</div>
        ) : (
          visible.map((shipment) => {
            const status = !shipment.planned
              ? "red"
              : shipment.onTime
              ? "green"
              : "yellow";

            const isCompleted = simMinute >= shipment.estimatedArrival;

            return (
              <div className="row" key={shipment.id}>
                <span className={`dot ${status}`}></span>
                <div className="row-main">
                  <strong>{shipment.clientId}</strong>
                  <span>
                    {`${shipment.origin} → ${shipment.destination} · ${shipment.suitcases} maletas`}
                  </span>
                  <span>
                    {`Pedido: ${formatSimMinute(shipment.requestMinute)} · Llegada: ${formatSimMinute(
                      shipment.estimatedArrival
                    )}`}
                  </span>
                  <div style={{ display: "flex", gap: "0.5rem", alignItems: "center", marginTop: "0.25rem" }}>
                    <button
                      onClick={() => setSelectedShipment(shipment)}
                      style={{
                        padding: "0.25rem 0.5rem",
                        fontSize: "0.75rem",
                        cursor: "pointer",
                        background: "#4a5568",
                        color: "#fff",
                        border: "none",
                        borderRadius: "4px",
                      }}
                    >
                      Ver vuelos ({shipment.flightIds.length})
                    </button>
                    <span
                      className="capacity-pill"
                      style={{
                        background: isCompleted ? "#2f855a" : "#ffbf00",
                        color: "#fff",
                      }}
                    >
                      {isCompleted ? "Entregado" : "En curso"}
                    </span>
                  </div>
                </div>
              </div>
            );
          })
        )}
      </div>

      {selectedShipment && (
        <FlightListModal
          shipment={selectedShipment}
          onClose={() => setSelectedShipment(null)}
        />
      )}
    </div>
  );
}