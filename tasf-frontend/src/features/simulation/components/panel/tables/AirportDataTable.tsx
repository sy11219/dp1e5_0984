import { useEffect, useState } from "react";
import type { AirportFlight, AirportShipment } from "../../../utils/airportRelations";
import { Button } from "@/components/ui/button";
import type { SimulationData } from "../../../types";
import { formatFlightMoment, formatSimMinute } from "../../../utils/formatters";

type ViewType = "incoming" | "outgoing" | "shipments";

type Props = {
  viewType: ViewType;
  flights: AirportFlight[];
  shipments: AirportShipment[];
  airportCode: string;
  data?: SimulationData | null;
  displayGmtOffset?: number;
  airportGmtOffset?: number;
};

const PAGE_SIZE_FLIGHTS = 15;
const PAGE_SIZE_SHIPMENTS = 50;
type ShipmentRoleFilter = "incoming" | "outgoing" | "all";

function roleLabel(role: AirportShipment["role"]) {
  return role === "origin" ? "Origen" : role === "destination" ? "Destino" : "Escala";
}

export function AirportDataTable({ viewType, flights, shipments, airportCode, data, displayGmtOffset, airportGmtOffset }: Props) {
  const [page, setPage] = useState(1);
  const [shipmentRoleFilter, setShipmentRoleFilter] = useState<ShipmentRoleFilter>("incoming");
  const formatMoment = (minute: number) =>
    data ? formatFlightMoment(data, minute, displayGmtOffset) : formatSimMinute(minute, displayGmtOffset ?? 0);
  const formatAirportMoment = (minute: number) =>
    data ? formatFlightMoment(data, minute, airportGmtOffset) : formatSimMinute(minute, airportGmtOffset ?? 0);

  // Resetear página cuando cambian los datos
  useEffect(() => {
    setPage(1);
  }, [viewType, flights.length, shipments.length, shipmentRoleFilter]);

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
          <table className="compact-table w-full" style={{ minWidth: 760 }}>
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
                    {formatMoment(flight.absoluteArrivalMinute)}
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
                    {formatMoment(flight.absoluteDepartureMinute)}
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
    const filteredShipments = shipmentRoleFilter === "incoming"
      ? shipments.filter(({ role }) => role === "destination")
      : shipmentRoleFilter === "outgoing"
        ? shipments.filter(({ role }) => role === "origin")
        : shipments;
    const total = filteredShipments.length;
    const visible = filteredShipments.slice((page - 1) * PAGE_SIZE_SHIPMENTS, page * PAGE_SIZE_SHIPMENTS);

    if (total === 0) {
      return (
        <div className="rounded-lg border bg-card p-4">
          <ShipmentFilterHeader
            airportCode={airportCode}
            total={total}
            value={shipmentRoleFilter}
            onChange={setShipmentRoleFilter}
          />
          <div className="empty-state">
            {shipmentRoleFilter === "incoming"
              ? `Sin envíos entrantes planificados en ${airportCode}.`
              : shipmentRoleFilter === "outgoing"
                ? `Sin envíos salientes planificados en ${airportCode}.`
              : `Sin envíos en ${airportCode}.`}
          </div>
        </div>
      );
    }

    return (
      <div className="rounded-lg border bg-card p-4">
        <ShipmentFilterHeader
          airportCode={airportCode}
          total={total}
          value={shipmentRoleFilter}
          onChange={setShipmentRoleFilter}
        />
        <div className="overflow-x-auto">
          <table className="compact-table w-full">
            <thead className="bg-muted/50">
              <tr>
                <th className="text-left" style={{ paddingRight: 24 }}>ID</th>
                <th className="text-left" style={{ paddingRight: 24 }}>Origen</th>
                <th className="text-left" style={{ paddingRight: 24 }}>Destino</th>
                <th className="text-left" style={{ paddingRight: 24 }}>Maletas</th>
                <th className="text-left" style={{ paddingRight: 24 }}>Registro local</th>
                <th className="text-left">Rol</th>
              </tr>
            </thead>
            <tbody>
              {visible.map(({ shipment, role }) => (
                <tr key={shipment.id} className="hover:bg-muted/30">
                  <td style={{ paddingRight: 24 }}><strong>{shipment.id}</strong></td>
                  <td style={{ paddingRight: 24 }}>{shipment.origin}</td>
                  <td style={{ paddingRight: 24 }}>{shipment.destination}</td>
                  <td style={{ paddingRight: 24 }}>{shipment.suitcases}</td>
                  <td style={{ paddingRight: 24, whiteSpace: "nowrap" }}>{formatAirportMoment(shipment.requestMinute)}</td>
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
function ShipmentFilterHeader({
  airportCode,
  total,
  value,
  onChange,
}: {
  airportCode: string;
  total: number;
  value: ShipmentRoleFilter;
  onChange: (value: ShipmentRoleFilter) => void;
}) {
  return (
    <div style={{ display: "flex", justifyContent: "space-between", gap: "0.75rem", alignItems: "center", marginBottom: 8 }}>
      <div>
        <h4 className="text-lg font-medium" style={{ margin: 0 }}>
          Envíos planificados del almacén {airportCode} ({total})
        </h4>
        <span className="text-sm text-muted-foreground">
          {value === "incoming"
            ? "Mostrando envíos que entran al almacén."
            : value === "outgoing"
              ? "Mostrando envíos que salen del almacén."
              : "Mostrando envíos de origen, destino y escala."}
        </span>
      </div>
      <label className="text-sm" style={{ display: "grid", gap: 4, minWidth: 130 }}>
        Vista
        <select value={value} onChange={(event) => onChange(event.target.value as ShipmentRoleFilter)}>
          <option value="incoming">Entrantes</option>
          <option value="outgoing">Salientes</option>
          <option value="all">Todos</option>
        </select>
      </label>
    </div>
  );
}

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
