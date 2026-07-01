import { useEffect, useState } from "react";
import type { AirportFlight, AirportShipment } from "../utils/airportRelations";
import { Button } from "@/components/ui/button";
import { formatSimMinute } from "../utils/formatters";

type ViewType = "incoming" | "outgoing" | "shipments";

type Props = {
  viewType: ViewType;
  flights: AirportFlight[];
  shipments: AirportShipment[];
  airportCode: string;
  displayGmtOffset?: number;
};

const PAGE_SIZE_FLIGHTS = 15;
const PAGE_SIZE_SHIPMENTS = 50;

function roleLabel(role: AirportShipment["role"]) {
  return role === "origin" ? "Origen" : role === "destination" ? "Destino" : "Escala";
}

export function AirportDataTable({ viewType, flights, shipments, airportCode, displayGmtOffset }: Props) {
  const [page, setPage] = useState(1);

  // Resetear página cuando cambian los datos
  useEffect(() => {
    setPage(1);
  }, [viewType, flights.length, shipments.length]);

  // ── Vista: Vuelos Entrantes ──────────────────────────────────────────────
  if (viewType === "incoming") {
    const incoming = flights.filter((f) => f.direction === "incoming");
    const total = incoming.length;
    const visible = incoming.slice((page - 1) * PAGE_SIZE_FLIGHTS, page * PAGE_SIZE_FLIGHTS);

    if (total === 0) {
      return <div className="empty-state">Sin vuelos entrantes en {airportCode}.</div>;
    }

    return (
      <div className="rounded-lg border bg-card p-4">
        <h4 className="text-lg font-medium mb-2">Llegadas ({total})</h4>
        <div className="overflow-x-auto">
          <table className="compact-table w-full">
            <thead className="bg-muted/50">
              <tr>
                <th className="text-left">Vuelo</th>
                <th className="text-left">Origen</th>
                <th className="text-right">Llegada</th>
                <th className="text-right">Maletas</th>
              </tr>
            </thead>
            <tbody>
              {visible.map(({ flight, counterpart, load }) => (
                <tr key={flight.id} className="hover:bg-muted/30">
                  <td><strong>{flight.id}</strong></td>
                  <td>{counterpart}</td>
                  <td className="text-right">
                    {formatSimMinute(flight.absoluteArrivalMinute, displayGmtOffset ?? 0)}
                  </td>
                  <td className="text-right">{load}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
        <PaginationControls page={page} setPage={setPage} total={total} pageSize={PAGE_SIZE_FLIGHTS} />
      </div>
    );
  }

  // ── Vista: Vuelos Salientes ──────────────────────────────────────────────
  if (viewType === "outgoing") {
    const outgoing = flights.filter((f) => f.direction === "outgoing");
    const total = outgoing.length;
    const visible = outgoing.slice((page - 1) * PAGE_SIZE_FLIGHTS, page * PAGE_SIZE_FLIGHTS);

    if (total === 0) {
      return <div className="empty-state">Sin vuelos salientes en {airportCode}.</div>;
    }

    return (
      <div className="rounded-lg border bg-card p-4">
        <h4 className="text-lg font-medium mb-2">Salidas ({total})</h4>
        <div className="overflow-x-auto">
          <table className="compact-table w-full">
            <thead className="bg-muted/50">
              <tr>
                <th className="text-left">Vuelo</th>
                <th className="text-left">Destino</th>
                <th className="text-right">Salida</th>
                <th className="text-right">Maletas</th>
              </tr>
            </thead>
            <tbody>
              {visible.map(({ flight, counterpart, load }) => (
                <tr key={flight.id} className="hover:bg-muted/30">
                  <td><strong>{flight.id}</strong></td>
                  <td>{counterpart}</td>
                  <td className="text-right">
                    {formatSimMinute(flight.absoluteDepartureMinute, displayGmtOffset ?? 0)}
                  </td>
                  <td className="text-right">{load}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
        <PaginationControls page={page} setPage={setPage} total={total} pageSize={PAGE_SIZE_FLIGHTS} />
      </div>
    );
  }

  // ── Vista: Envíos ────────────────────────────────────────────────────────
  if (viewType === "shipments") {
    const total = shipments.length;
    const visible = shipments.slice((page - 1) * PAGE_SIZE_SHIPMENTS, page * PAGE_SIZE_SHIPMENTS);

    if (total === 0) {
      return <div className="empty-state">Sin envíos en {airportCode}.</div>;
    }

    return (
      <div className="rounded-lg border bg-card p-4">
        <h4 className="text-lg font-medium mb-2">Envíos ({total})</h4>
        <div className="overflow-x-auto">
          <table className="compact-table w-full">
            <thead className="bg-muted/50">
              <tr>
                <th className="text-left">ID</th>
                <th className="text-left">Origen</th>
                <th className="text-left">Destino</th>
                <th className="text-left">Maletas</th>
                <th className="text-left">Rol</th>
              </tr>
            </thead>
            <tbody>
              {visible.map(({ shipment, role }) => (
                <tr key={shipment.id} className="hover:bg-muted/30">
                  <td><strong>{shipment.id}</strong></td>
                  <td>{shipment.origin}</td>
                  <td>{shipment.destination}</td>
                  <td>{shipment.suitcases}</td>
                  <td>{roleLabel(role)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
        <PaginationControls page={page} setPage={setPage} total={total} pageSize={PAGE_SIZE_SHIPMENTS} />
      </div>
    );
  }

  return null;
}

// ── Componente auxiliar para paginación ──────────────────────────────────────
function PaginationControls({
  page,
  setPage,
  total,
  pageSize,
}: {
  page: number;
  setPage: (p: number) => void;
  total: number;
  pageSize: number;
}) {
  return (
    <div className="flex items-center justify-between mt-3">
      <span className="text-sm text-muted-foreground">
        {total ? `${(page - 1) * pageSize + 1}-${Math.min(page * pageSize, total)} de ${total}` : "0 de 0"}
      </span>
      <div className="flex items-center gap-2">
        <Button variant="ghost" size="sm" onClick={() => setPage(Math.max(1, page - 1))} disabled={page <= 1}>
          Anterior
        </Button>
        <Button variant="ghost" size="sm" onClick={() => setPage(page + 1)} disabled={page >= Math.ceil(total / pageSize)}>
          Siguiente
        </Button>
      </div>
    </div>
  );
}
