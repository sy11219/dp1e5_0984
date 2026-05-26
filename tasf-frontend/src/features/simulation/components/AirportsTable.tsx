import type { Airport, AirportLoads } from "../types";
import { STATUS_COLOR } from "../utils/constants";
import { capacityStatus } from "../utils/calculations";

interface AirportsTableProps {
  airports: Airport[];
  loads: AirportLoads;
}

export function AirportsTable({ airports, loads }: AirportsTableProps) {
  const ordered = [...airports].sort(
    (a, b) =>
      (loads[b.code] || 0) / b.maxCapacity - (loads[a.code] || 0) / a.maxCapacity
  );

  return (
    <div className="table">
      {ordered.slice(0, 10).map((airport) => {
        const load = loads[airport.code] || 0;
        const utilization = airport.maxCapacity ? load / airport.maxCapacity : 0;
        const status = capacityStatus(utilization);

        return (
          <div className="row" key={airport.code}>
            <span className={`dot ${status}`}></span>
            <div className="row-main">
              <strong>{`${airport.code} · ${airport.city}`}</strong>
              <span>{`${load}/${airport.maxCapacity} maletas`}</span>
            </div>
            <span
              className="capacity-pill"
              style={{
                background: STATUS_COLOR[status],
              }}
            >
              {`${Math.round(utilization * 100)}%`}
            </span>
          </div>
        );
      })}
    </div>
  );
}
