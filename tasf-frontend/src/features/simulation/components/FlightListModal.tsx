import type { Shipment } from "../types";
import "./flightListModal.css";

interface FlightListModalProps {
  shipment: Shipment;
  onClose: () => void;
}

export function FlightListModal({ shipment, onClose }: FlightListModalProps) {
  return (
    <div className="modal-overlay" onClick={onClose}>
      <div className="modal-content" onClick={(e) => e.stopPropagation()}>
        <h3>Vuelos del envío {shipment.clientId}</h3>
        <div className="flight-list">
          {shipment.flightIds.map((flightId) => {
            const parts = flightId.split("-");
            const origin = parts[0] || "???";
            const destination = parts[1] || "???";
            const departureTime = parts[3] || "0000";
            const arrivalTime = parts[4] || "0000";
            const formattedDeparture = `${departureTime.slice(0, 2)}:${departureTime.slice(2)}`;
            const formattedArrival = `${arrivalTime.slice(0, 2)}:${arrivalTime.slice(2)}`;

            return (
              <div key={flightId} className="flight-item">
                <div>{origin} → {destination}</div>
                <div>{formattedDeparture} - {formattedArrival}</div>
              </div>
            );
          })}
        </div>
        <button onClick={onClose}>Cerrar</button>
      </div>
    </div>
  );
}
