import { useEffect, useMemo, useState } from "react";
import {
  advanceRealtimeSessionRequest,
  cancelRealtimeFlightRequest,
  getCurrentRealtimeSessionRequest,
  startRealtimeSessionRequest,
} from "../../../api/simulationApi";
import { Navbar } from "../../../shared/components/Navbar/Navbar";
import { AirportDetail } from "../../simulation/components/AirportDetail";
import { AirportsTable } from "../../simulation/components/AirportsTable";
import { FlightsTable } from "../../simulation/components/FlightsTable";
import MapStage from "../../simulation/components/simulation/map/MapStage";
import type { AirportLoads, SimulationData } from "../../simulation/types";
import { computeActiveFlights } from "../../simulation/utils/calculations";
import {
  formatClock,
  formatDateOnly,
  formatFlightMoment,
  formatTimeOnly,
  percent,
} from "../../simulation/utils/formatters";

function formatOperationalMinute(value: number) {
  const minute = Math.max(0, Math.floor(value));
  const day = Math.floor(minute / 1440);
  const dayMinute = minute % 1440;
  const hour = Math.floor(dayMinute / 60);
  const min = dayMinute % 60;
  const time = `${String(hour).padStart(2, "0")}:${String(min).padStart(2, "0")}`;
  return day > 0 ? `Dia ${day} ${time}` : time;
}

function StatusItem({
  label,
  value,
  sub,
}: {
  label: string;
  value: string;
  sub?: string;
}) {
  return (
    <div className="status-item">
      <span>{label}</span>
      <strong>{value}</strong>
      {sub && <small>{sub}</small>}
    </div>
  );
}

function OperationsTopbar({
  data,
  now,
  operationalMinute,
}: {
  data: SimulationData | null;
  now: Date;
  operationalMinute: number;
}) {
  return (
    <header className="topbar">
      <div className="brand">
        <strong>TASF.B2B - Tiempo real</strong>
        <span>Tiempo real</span>
      </div>
      <div className="status-strip">
        <StatusItem label="Hora actual" value={formatClock(now)} sub={formatDateOnly(now)} />
        <StatusItem
          label="Minuto operativo"
          value={formatOperationalMinute(operationalMinute)}
          sub={data ? formatFlightMoment(data, operationalMinute) : "avanza con el reloj real"}
        />
        <StatusItem
          label="Estado"
          value={data?.status === "COMPLETED" ? "Cerrada" : data ? "En vivo" : "--"}
          sub={data?.scenario || "--"}
        />
        <StatusItem
          label="Ultima lectura"
          value={data ? formatTimeOnly(data.realFinishedAt) : "--"}
          sub={data ? formatDateOnly(data.realFinishedAt) : "--"}
        />
        <StatusItem
          label="Pedidos procesados"
          value={String(data?.metrics.plannedShipments || 0)}
          sub={`de ${data?.metrics.shipments || 0}`}
        />
        <StatusItem
          label="Cola pendiente"
          value={String(data?.metrics.queuedShipments || 0)}
          sub="pedidos esperando ruta"
        />
        <StatusItem
          label="Vuelos usados"
          value={String(data?.metrics.usedFlights || 0)}
          sub="con carga asignada"
        />
        <StatusItem
          label="Backend"
          value={data?.simulationId ? "Conectado" : "--"}
          sub="Tiempo real"
        />
      </div>
    </header>
  );
}

function LiveMetrics({
  data,
  airportLoads,
}: {
  data: SimulationData;
  airportLoads: AirportLoads;
}) {
  const activeAirports = Object.values(airportLoads).filter((load) => load > 0).length;
  const plannedPct = percent(data.metrics.plannedShipments, data.metrics.shipments);
  const onTimePct = percent(data.metrics.onTimeShipments, data.metrics.shipments);

  return (
    <div className="metrics">
      <div className="metric">
        <span>Pedidos con ruta</span>
        <strong>{data.metrics.plannedShipments}</strong>
        <span>{`${plannedPct}% del total`}</span>
      </div>
      <div className="metric">
        <span>En cola</span>
        <strong>{data.metrics.queuedShipments || 0}</strong>
        <span>pendientes de asignacion</span>
      </div>
      <div className="metric">
        <span>Maletas asignadas</span>
        <strong>{data.metrics.plannedBags}</strong>
        <span>{`de ${data.metrics.totalBags}`}</span>
      </div>
      <div className="metric">
        <span>A tiempo</span>
        <strong>{`${onTimePct}%`}</strong>
        <span>{`${data.metrics.onTimeShipments} pedidos`}</span>
      </div>
      <div className="metric">
        <span>Vuelos con carga</span>
        <strong>{data.metrics.usedFlights}</strong>
        <span>operando equipaje</span>
      </div>
      <div className="metric">
        <span>Aeropuertos activos</span>
        <strong>{activeAirports}</strong>
        <span>con carga actual</span>
      </div>
    </div>
  );
}

export const OperationsPage = () => {
  const [data, setData] = useState<SimulationData | null>(null);
  const [loading, setLoading] = useState(true);
  const [advancing, setAdvancing] = useState(false);
  const [cancelling, setCancelling] = useState(false);
  const [error, setError] = useState("");
  const [notice, setNotice] = useState("");
  const [flightToCancel, setFlightToCancel] = useState("");
  const [selectedAirport, setSelectedAirport] = useState<string | null>(null);
  const [now, setNow] = useState(new Date());

  const operationalMinute = useMemo(() => {
    if (!data?.tick || !data.realFinishedAt) return data?.tick || 0;
    const elapsedMinutes = (now.getTime() - new Date(data.realFinishedAt).getTime()) / 60000;
    return Math.min(data.maxTick || Number.POSITIVE_INFINITY, data.tick + Math.max(0, elapsedMinutes));
  }, [data, now]);

  useEffect(() => {
    const timer = window.setInterval(() => setNow(new Date()), 1000);
    return () => window.clearInterval(timer);
  }, []);

  const connectOperation = async () => {
      setLoading(true);
      setError("");
      setNotice("");

    try {
      const payload = await startRealtimeSessionRequest();
      setData(payload);
      setSelectedAirport(payload.airports[0]?.code || null);
    } catch (err) {
      setError(err instanceof Error ? err.message : "No se pudo conectar la operacion.");
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    let ignore = false;

    void getCurrentRealtimeSessionRequest()
      .then((payload) => {
        if (ignore) return;
        if (payload) {
          setData(payload);
          setSelectedAirport(payload.airports[0]?.code || null);
          return;
        }
        return startRealtimeSessionRequest().then((created) => {
          if (ignore) return;
          setData(created);
          setSelectedAirport(created.airports[0]?.code || null);
        });
      })
      .catch((err) => {
        if (!ignore) {
          setError(err instanceof Error ? err.message : "No se pudo conectar la operacion.");
        }
      })
      .finally(() => {
        if (!ignore) setLoading(false);
      });

    return () => {
      ignore = true;
    };
  }, []);

  useEffect(() => {
    if (!data?.simulationId || data.status === "COMPLETED" || advancing) return;
    const simulationId = data.simulationId;
    const timer = window.setInterval(() => {
      setAdvancing(true);
      setError("");
      void advanceRealtimeSessionRequest(simulationId, 1, data.tick ?? 0)
        .then((payload) => {
          setData(payload);
          if (!selectedAirport) {
            setSelectedAirport(payload.airports[0]?.code || null);
          }
        })
        .catch((err) => {
          setError(err instanceof Error ? err.message : "No se pudo actualizar la operacion.");
        })
        .finally(() => setAdvancing(false));
    }, 60_000);
    return () => window.clearInterval(timer);
  }, [data?.simulationId, data?.status, data?.tick, advancing, selectedAirport]);

  const airportLoads = useMemo<AirportLoads>(() => {
    if (!data) return {};
    return Object.fromEntries(
      data.airports.map((airport) => [airport.code, airport.finalLoad || 0])
    );
  }, [data]);
  const activeFlights = useMemo(
    () => computeActiveFlights(data, operationalMinute),
    [data, operationalMinute]
  );
  const cancellableFlights = useMemo(() => {
    if (!data) return [];
    const cancelled = new Set(data.cancelledFlightIds || []);
    return data.flights
      .filter((flight) => flight.absoluteDepartureMinute > operationalMinute)
      .filter((flight) => !cancelled.has(flight.id))
      .slice(0, 100);
  }, [data, operationalMinute]);
  const selected = data?.airports.find((airport) => airport.code === selectedAirport);

  const cancelFlight = async () => {
    if (!data?.simulationId || !flightToCancel.trim()) return;
    setCancelling(true);
    setError("");
    setNotice("");
    try {
      const updated = await cancelRealtimeFlightRequest(data.simulationId, flightToCancel.trim());
      setData(updated);
      setFlightToCancel("");
      setNotice("Cancelacion registrada. Se aplicara en la siguiente ejecucion de Tiempo real.");
    } catch (err) {
      setError(err instanceof Error ? err.message : "No se pudo cancelar el vuelo.");
    } finally {
      setCancelling(false);
    }
  };

  return (
    <div className="app-shell">
      <Navbar />
      <OperationsTopbar data={data} now={now} operationalMinute={operationalMinute} />

      <main className="workspace">
        <aside className="side-panel">
          <section className="panel section">
            <h2>Tiempo real</h2>
            <div className="control-grid">
              <div className="metric">
                <span>Reloj operativo</span>
                <strong>{formatClock(now)}</strong>
                <span>{advancing ? "actualizando desde backend" : "sin adelanto manual"}</span>
              </div>

              <div className="metric">
                <span>Cola de vuelos</span>
                <strong>{data?.flightQueueSize ?? data?.metrics.flightQueueSize ?? 0}</strong>
                <span>{`${data?.realtimeWindowMinutes ?? 60} min por ejecucion`}</span>
              </div>

              <div className="metric">
                <span>Cancelaciones pendientes</span>
                <strong>{data?.pendingCancellationCount ?? data?.metrics.pendingCancellations ?? 0}</strong>
                <span>siguiente ejecucion</span>
              </div>

              <button className="primary" onClick={connectOperation} disabled={loading}>
                {loading ? "Conectando..." : "Reconectar operacion"}
              </button>

              <div className="field">
                <label>Cancelar vuelo</label>
                <select
                  value={flightToCancel}
                  onChange={(event) => setFlightToCancel(event.target.value)}
                  disabled={!cancellableFlights.length || cancelling}
                >
                  <option value="">Selecciona un vuelo futuro</option>
                  {cancellableFlights.map((flight) => (
                    <option key={flight.id} value={flight.id}>
                      {`${flight.id} · ${flight.origin}-${flight.destination}`}
                    </option>
                  ))}
                </select>
              </div>

              <button
                className="secondary"
                onClick={cancelFlight}
                disabled={!flightToCancel || cancelling}
              >
                {cancelling ? "Registrando..." : "Registrar cancelacion"}
              </button>

              {error && <div className="error">{error}</div>}
              {notice && <div className="success">{notice}</div>}
            </div>
          </section>

          <section className="panel section">
            <h3>Indicadores</h3>
            {data ? (
              <LiveMetrics data={data} airportLoads={airportLoads} />
            ) : (
              <div className="empty-state">Conecta la operacion para ver indicadores.</div>
            )}
          </section>
        </aside>

        <section className="panel map-panel live-map-panel">
          <MapStage
            data={data}
            activeFlights={activeFlights}
            airportLoads={airportLoads}
            selectedAirport={selectedAirport}
            onSelectAirport={setSelectedAirport}
          />
        </section>

        <aside className="right-panel">
          <section className="panel section">
            <h3>{selected ? `${selected.code} - ${selected.city}` : "Aeropuerto"}</h3>
            {selected ? (
              <AirportDetail
                airport={selected}
                load={airportLoads[selected.code] || 0}
                peakLabel="Carga registrada"
              />
            ) : (
              <div className="empty-state">Selecciona un aeropuerto.</div>
            )}
          </section>

          <section className="panel section">
            <h3>Vuelos activos</h3>
            <FlightsTable flights={activeFlights} />
          </section>

          <section className="panel section">
            <h3>Aeropuertos criticos</h3>
            {data ? (
              <AirportsTable airports={data.airports} loads={airportLoads} />
            ) : (
              <div className="empty-state">Sin datos.</div>
            )}
          </section>
        </aside>
      </main>
    </div>
  );
};
