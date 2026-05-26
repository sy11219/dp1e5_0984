import type { Flight } from "../types";
import { STATUS_COLOR } from "../utils/constants";
import { hhmm } from "../utils/formatters";

interface FlightsTableProps {
  flights: Flight[];
}

export function FlightsTable({ flights }: FlightsTableProps) {
  if (!flights.length) {
    return <div className="empty-state">No hay vuelos activos en este minuto.</div>;
  }

  return (
    <div className="table">
      {flights.slice(0, 10).map((flight) => (
        <div className="row" key={flight.id}>
          <span className={`dot ${flight.status}`}></span>
          <div className="row-main">
            <strong>{`${flight.origin} -> ${flight.destination}`}</strong>
            <span>{`Dia ${flight.dayOffset} · ${hhmm(flight.departureMinute)}-${hhmm(flight.arrivalMinute)}`}</span>
          </div>
          <span
            className="capacity-pill"
            style={{
              background: STATUS_COLOR[flight.status],
            }}
          >
            {`${Math.round(flight.utilization * 100)}%`}
          </span>
        </div>
      ))}
    </div>
  );
}
