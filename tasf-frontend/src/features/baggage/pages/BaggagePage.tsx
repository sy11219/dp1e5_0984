import { useEffect, useMemo, useState } from "react";
import {
  refreshRealtimeOperationRequest,
  runRealtimeOperationRequest,
} from "../../../api/simulationApi";
import { Navbar } from "../../../shared/components/Navbar/Navbar";
import type { CapacityStatus, SimulationData } from "../../simulation/types";

type AirportQueue = {
  code: string;
  city: string;
  bags: number;
  pending: number;
  status: CapacityStatus;
};

type Incident = {
  id: string;
  route: string;
  reason: string;
  eta: string;
};

function statusFromUtilization(utilization: number): CapacityStatus {
  if (utilization < 0.7) return "green";
  if (utilization < 0.9) return "yellow";
  return "red";
}

function buildAirportQueues(data: SimulationData): AirportQueue[] {
  return data.airports
    .map((airport) => {
      const relatedShipments = data.shipments.filter(
        (shipment) =>
          shipment.origin === airport.code || shipment.destination === airport.code
      );
      const bags = relatedShipments.reduce(
        (sum, shipment) => sum + shipment.suitcases,
        0
      );
      const pending = relatedShipments.reduce(
        (sum, shipment) =>
          !shipment.planned || !shipment.onTime ? sum + shipment.suitcases : sum,
        0
      );

      return {
        code: airport.code,
        city: airport.city,
        bags,
        pending,
        status: airport.status || statusFromUtilization(airport.utilization),
      };
    })
    .filter((queue) => queue.bags > 0)
    .sort((a, b) => b.pending - a.pending || b.bags - a.bags)
    .slice(0, 8);
}

function buildIncidents(data: SimulationData): Incident[] {
  return data.shipments
    .filter((shipment) => !shipment.planned || !shipment.onTime)
    .sort((a, b) => b.delayMinutes - a.delayMinutes || b.suitcases - a.suitcases)
    .slice(0, 6)
    .map((shipment) => ({
      id: shipment.id,
      route: `${shipment.origin} -> ${shipment.destination}`,
      reason: shipment.planned ? "Retraso frente al SLA" : "Sin ruta asignada",
      eta: shipment.planned
        ? `${Math.max(0, Math.round(shipment.delayMinutes))} min`
        : "Pendiente",
    }));
}

function buildTransferLanes(data: SimulationData) {
  const lanes = [
    { name: "Vuelos en verde", status: "green" as CapacityStatus },
    { name: "Vuelos en amarillo", status: "yellow" as CapacityStatus },
    { name: "Vuelos en rojo", status: "red" as CapacityStatus },
  ];

  return lanes.map((lane) => {
    const flights = data.flights.filter((flight) => flight.status === lane.status);
    return {
      name: lane.name,
      processed: flights.reduce((sum, flight) => sum + flight.assignedLoad, 0),
      target: flights.reduce((sum, flight) => sum + flight.maxCapacity, 0),
    };
  });
}

export const BaggagePage = () => {
  const [data, setData] = useState<SimulationData | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState("");

  const loadData = async () => {
    setLoading(true);
    setError("");

    try {
      const payload = await refreshRealtimeOperationRequest();
      setData(payload);
    } catch (err) {
      setError(err instanceof Error ? err.message : "No se pudo cargar maletas.");
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    let ignore = false;

    void runRealtimeOperationRequest()
      .then((payload) => {
        if (!ignore) setData(payload);
      })
      .catch((err) => {
        if (!ignore) {
          setError(err instanceof Error ? err.message : "No se pudo cargar maletas.");
        }
      })
      .finally(() => {
        if (!ignore) setLoading(false);
      });

    return () => {
      ignore = true;
    };
  }, []);

  const baggageQueues = useMemo(
    () => (data ? buildAirportQueues(data) : []),
    [data]
  );
  const incidents = useMemo(() => (data ? buildIncidents(data) : []), [data]);
  const transferLanes = useMemo(
    () => (data ? buildTransferLanes(data) : []),
    [data]
  );

  const totalBags = data?.metrics.totalBags || 0;
  const pendingBags = baggageQueues.reduce((sum, item) => sum + item.pending, 0);
  const readyBags = Math.max(0, (data?.metrics.plannedBags || 0) - pendingBags);

  return (
    <div className="app-shell">
      <Navbar />

      <main className="dashboard-workspace">
        <section className="dashboard-heading">
          <div>
            <h1>Gestion de maletas</h1>
            <p>Monitoreo de carga, conexiones y pendientes por aeropuerto.</p>
          </div>
          <button className="primary" onClick={loadData} disabled={loading}>
            {loading ? "Actualizando..." : "Actualizar estado"}
          </button>
        </section>

        {error && <div className="error">{error}</div>}

        <section className="dashboard-grid">
          <div className="panel section metric-panel">
            <span>Maletas en sistema</span>
            <strong>{totalBags.toLocaleString("es-PE")}</strong>
            <small>{readyBags.toLocaleString("es-PE")} listas para embarque</small>
          </div>
          <div className="panel section metric-panel">
            <span>Pendientes</span>
            <strong>{pendingBags.toLocaleString("es-PE")}</strong>
            <small>revision o conexion activa</small>
          </div>
          <div className="panel section metric-panel">
            <span>Incidencias</span>
            <strong>{incidents.length}</strong>
            <small>requieren seguimiento operativo</small>
          </div>
        </section>

        <section className="management-grid">
          <div className="panel section">
            <h2>Colas por aeropuerto</h2>
            {baggageQueues.length ? (
              <div className="table">
                {baggageQueues.map((queue) => (
                  <div className="row" key={queue.code}>
                    <span className={`dot ${queue.status}`}></span>
                    <div className="row-main">
                      <strong>{`${queue.code} - ${queue.city}`}</strong>
                      <span>{`${queue.pending} pendientes de ${queue.bags} maletas`}</span>
                    </div>
                    <span className="capacity-pill">{`${Math.round(
                      (queue.pending / queue.bags) * 100
                    )}%`}</span>
                  </div>
                ))}
              </div>
            ) : (
              <div className="empty-state">
                {loading ? "Cargando datos del backend..." : "Sin maletas para mostrar."}
              </div>
            )}
          </div>

          <div className="panel section">
            <h2>Incidencias recientes</h2>
            {incidents.length ? (
              <div className="table">
                {incidents.map((incident) => (
                  <div className="row" key={incident.id}>
                    <span className="dot yellow"></span>
                    <div className="row-main">
                      <strong>{incident.id}</strong>
                      <span>{`${incident.route} - ${incident.reason}`}</span>
                    </div>
                    <span className="capacity-pill">{incident.eta}</span>
                  </div>
                ))}
              </div>
            ) : (
              <div className="empty-state">
                {loading ? "Cargando incidencias..." : "No hay incidencias en la simulacion."}
              </div>
            )}
          </div>

          <div className="panel section">
            <h2>Bandas de transferencia</h2>
            {transferLanes.length ? (
              <div className="lane-list">
                {transferLanes.map((lane) => {
                  const progress = lane.target
                    ? Math.round((lane.processed / lane.target) * 100)
                    : 0;
                  return (
                    <div className="lane" key={lane.name}>
                      <div className="lane-meta">
                        <strong>{lane.name}</strong>
                        <span>{`${lane.processed}/${lane.target}`}</span>
                      </div>
                      <div className="lane-bar">
                        <span style={{ width: `${Math.min(progress, 100)}%` }} />
                      </div>
                    </div>
                  );
                })}
              </div>
            ) : (
              <div className="empty-state">Sin vuelos procesados.</div>
            )}
          </div>
        </section>
      </main>
    </div>
  );
};
