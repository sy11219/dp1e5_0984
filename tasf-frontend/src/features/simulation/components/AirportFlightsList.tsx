import { useState, useMemo } from "react";
import type { AirportFlight } from "../utils/airportRelations";
import { Button } from "@/components/ui/button";
import { formatSimMinute } from "../utils/formatters";

type Props = {
  flights: AirportFlight[];
};

const PAGE_SIZE = 15;

export function AirportFlightsList({ flights }: Props) {
  if (flights.length === 0) {
    return <div className="empty-state">Sin vuelos en este aeropuerto.</div>;
  }

  const incoming = useMemo(() => flights.filter((f) => f.direction === "incoming"), [flights]);
  const outgoing = useMemo(() => flights.filter((f) => f.direction === "outgoing"), [flights]);

  const [incomingPage, setIncomingPage] = useState(1);
  const [outgoingPage, setOutgoingPage] = useState(1);

  // Reset pages when counts change
  useMemo(() => setIncomingPage(1), [incoming.length]);
  useMemo(() => setOutgoingPage(1), [outgoing.length]);

  const incomingTotal = incoming.length;
  const outgoingTotal = outgoing.length;

  const incomingVisible = incoming.slice((incomingPage - 1) * PAGE_SIZE, incomingPage * PAGE_SIZE);
  const outgoingVisible = outgoing.slice((outgoingPage - 1) * PAGE_SIZE, outgoingPage * PAGE_SIZE);

  return (
    <div className="grid md:grid-cols-2 gap-4">
      <div className="rounded-lg border bg-card p-4">
        <div className="flex items-center justify-between">
          <h4 className="text-lg font-medium">Llegada ({incomingTotal})</h4>
        </div>

        {incomingTotal === 0 ? (
          <div className="empty-state">Sin vuelos entrantes.</div>
        ) : (
          <>
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
                  {incomingVisible.map(({ flight, counterpart, load }) => (
                    <tr key={flight.id} className="hover:bg-muted/30">
                        <td>
                          <strong>{flight.id}</strong>
                        </td>
                        <td>{counterpart}</td>
                      <td className="text-right">{formatSimMinute(flight.absoluteArrivalMinute)}</td>
                        <td className="text-right">{load}</td>
                      </tr>
                  ))}
                </tbody>
              </table>
            </div>

            <div className="flex items-center justify-between mt-3">
              <span className="text-sm text-muted-foreground">
                {incomingTotal ? `${(incomingPage - 1) * PAGE_SIZE + 1}-${Math.min(incomingPage * PAGE_SIZE, incomingTotal)} de ${incomingTotal}` : "0 de 0"}
              </span>
              <div className="flex items-center gap-2">
                <Button variant="ghost" size="sm" onClick={() => setIncomingPage((p) => Math.max(1, p - 1))} disabled={incomingPage <= 1}>
                  Anterior
                </Button>
                <Button variant="ghost" size="sm" onClick={() => setIncomingPage((p) => p + 1)} disabled={incomingPage >= Math.ceil(incomingTotal / PAGE_SIZE)}>
                  Siguiente
                </Button>
              </div>
            </div>
          </>
        )}
      </div>

      <div className="rounded-lg border bg-card p-4">
        <div className="flex items-center justify-between">
          <h4 className="text-lg font-medium">Salida ({outgoingTotal})</h4>
        </div>

        {outgoingTotal === 0 ? (
          <div className="empty-state">Sin vuelos salientes.</div>
        ) : (
          <>
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
                  {outgoingVisible.map(({ flight, counterpart, load }) => (
                    <tr key={flight.id} className="hover:bg-muted/30">
                      <td>
                        <strong>{flight.id}</strong>
                      </td>
                      <td>{counterpart}</td>
                      <td className="text-right">{formatSimMinute(flight.absoluteDepartureMinute)}</td>
                      <td className="text-right">{load}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>

            <div className="flex items-center justify-between mt-3">
              <span className="text-sm text-muted-foreground">
                {outgoingTotal ? `${(outgoingPage - 1) * PAGE_SIZE + 1}-${Math.min(outgoingPage * PAGE_SIZE, outgoingTotal)} de ${outgoingTotal}` : "0 de 0"}
              </span>
              <div className="flex items-center gap-2">
                <Button variant="ghost" size="sm" onClick={() => setOutgoingPage((p) => Math.max(1, p - 1))} disabled={outgoingPage <= 1}>
                  Anterior
                </Button>
                <Button variant="ghost" size="sm" onClick={() => setOutgoingPage((p) => p + 1)} disabled={outgoingPage >= Math.ceil(outgoingTotal / PAGE_SIZE)}>
                  Siguiente
                </Button>
              </div>
            </div>
          </>
        )}
      </div>
    </div>
  );
}