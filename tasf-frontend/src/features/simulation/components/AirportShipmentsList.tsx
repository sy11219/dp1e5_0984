import { useState, useMemo } from "react";
import type { AirportShipment } from "../utils/airportRelations";
import { Button } from "@/components/ui/button";

type Props = {
  shipments: AirportShipment[];
};

const PAGE_SIZE = 50;

function roleLabel(role: AirportShipment["role"]) {
  return role === "origin" ? "Origen" : role === "destination" ? "Destino" : "Escala";
}

export function AirportShipmentsList({ shipments }: Props) {
  if (shipments.length === 0) {
    return <div className="empty-state">Sin envíos en este aeropuerto.</div>;
  }

  const [page, setPage] = useState(1);
  useMemo(() => setPage(1), [shipments.length]);

  const total = shipments.length;
  const visible = shipments.slice((page - 1) * PAGE_SIZE, page * PAGE_SIZE);

  return (
    <div className="mx-auto max-w-2xl rounded-lg border bg-card p-4">
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
                <td>
                  <strong>{shipment.id}</strong>
                </td>
                <td>{shipment.origin}</td>
                <td>{shipment.destination}</td>
                <td className="text-left">{shipment.suitcases}</td>
                <td>{roleLabel(role)}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>

      <div className="flex items-center justify-between mt-3">
        <span className="text-sm text-muted-foreground">
          {total ? `${(page - 1) * PAGE_SIZE + 1}-${Math.min(page * PAGE_SIZE, total)} de ${total}` : "0 de 0"}
        </span>
        <div className="flex items-center gap-2">
          <Button variant="ghost" size="sm" onClick={() => setPage((p) => Math.max(1, p - 1))} disabled={page <= 1}>
            Anterior
          </Button>
          <Button variant="ghost" size="sm" onClick={() => setPage((p) => p + 1)} disabled={page >= Math.ceil(total / PAGE_SIZE)}>
            Siguiente
          </Button>
        </div>
      </div>
    </div>
  );
}