import { useMemo } from "react";
import type { Flight, Shipment, SimulationData } from "../../types";
import { formatFlightMoment, formatSimMinute } from "../../utils/formatters";
import { resolveShipmentRoute } from "../../utils/shipmentRoute";
import "./flightListModal.css";

interface FlightListModalProps {
  shipment: Shipment;
  flights: Flight[];
  data?: SimulationData | null;
  onClose: () => void;
  onSelectFlight?: (id: string) => void;
}

function formatWait(minutes?: number) {
  if (minutes === undefined) return "";
  const h = Math.floor(minutes / 60);
  const m = minutes % 60;
  if (h <= 0) return `${m} min`;
  return `${h} h ${String(m).padStart(2, "0")} min`;
}

export function FlightListModal({
  shipment,
  flights,
  data,
  onClose,
  onSelectFlight,
}: FlightListModalProps) {
  const route = useMemo(
    () => resolveShipmentRoute(shipment, flights),
    [flights, shipment]
  );
  const initialWait =
    route[0]?.absoluteDepartureMinute !== undefined
      ? Math.max(0, route[0].absoluteDepartureMinute - shipment.requestMinute)
      : undefined;

  return (
    <div className="modal-overlay" onClick={onClose}>
      <div className="modal-content" onClick={(event) => event.stopPropagation()}>
        <h3>Ruta del envio {shipment.id}</h3>
        <p className="route-modal-summary">
          {`${shipment.origin} -> ${shipment.destination} - ${shipment.suitcases} maletas - pedido ${
            data ? formatFlightMoment(data, shipment.requestMinute) : formatSimMinute(shipment.requestMinute)
          }`}
        </p>

        {initialWait !== undefined && (
          <div className="route-scale-card">
            <strong>Espera inicial</strong>
            <span>{formatWait(initialWait)}</span>
          </div>
        )}

        <div className="flight-list">
          {route.map((leg, index) => {
            const departure =
              leg.absoluteDepartureMinute !== undefined
                ? formatFlightMoment(data, leg.absoluteDepartureMinute)
                : "No disponible";
            const arrival =
              leg.absoluteArrivalMinute !== undefined
                ? formatFlightMoment(data, leg.absoluteArrivalMinute)
                : "No disponible";

            return (
              <div key={`${leg.flightId}-${index}`} className="route-leg-block">
                {index > 0 && (
                  <div className="route-scale-card">
                    <strong>{`Escala en ${leg.transferAirport ?? leg.origin}`}</strong>
                    <span>{formatWait(leg.waitBeforeMinutes)}</span>
                  </div>
                )}
                <div className="flight-item">
                  <div>
                    <strong>{`${index + 1}. ${leg.origin} -> ${leg.destination}`}</strong>
                  </div>
                  <div>ID: {leg.flightId}</div>
                  <div>Salida: {departure}</div>
                  <div>Llegada: {arrival}</div>
                  {leg.assignedLoad !== undefined && leg.maxCapacity !== undefined && (
                    <div>{`Carga: ${leg.assignedLoad}/${leg.maxCapacity} maletas`}</div>
                  )}
                  {onSelectFlight && (
                    <button
                      type="button"
                      className="route-focus-button"
                      onClick={() => {onSelectFlight(leg.flightId); onClose();}}
                    >
                      Enfocar vuelo
                    </button>
                  )}
                </div>
              </div>
            );
          })}
        </div>

        {!route.length && <div className="empty-state">El envio no tiene ruta asignada.</div>}
        <button onClick={onClose}>Cerrar</button>
      </div>
    </div>
  );
}
