import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { PanelLeftClose, PanelLeftOpen, PanelRightClose, PanelRightOpen } from "lucide-react";
import {
  cancelRealtimeFlightRequest,
  getCurrentRealtimeSessionRequest,
  pauseRealtimeSessionRequest,
  startRealtimeSessionRequest,
} from "../../../api/simulationApi";
import { Navbar } from "../../../shared/components/Navbar/Navbar";
import { AirportDetail } from "../../simulation/components/AirportDetail";
import { AirportsTable } from "../../simulation/components/AirportsTable";
import { CapacityLegend } from "../../simulation/components/CapacityLegend";
import { FlightsTable } from "../../simulation/components/FlightsTable";
import { GlobalIndicators } from "../../simulation/components/GlobalIndicators";
import { ShipmentsTable } from "../../simulation/components/ShipmentsTable";
import { SimulationResultModal } from "../../simulation/components/SimulationResultModal";
import MapStage, { type MapFocusTarget } from "../../simulation/components/simulation/map/MapStage";
import type { AirportLoads, Shipment, SimulationData } from "../../simulation/types";
import { computeActiveFlights, computeAirportLoads } from "../../simulation/utils/calculations";
import { readMapFocus, writeMapFocus } from "../../simulation/utils/mapFocusStorage";
import {
  formatClock,
  formatDateOnly,
  percent,
} from "../../simulation/utils/formatters";
import { useAssignedAirportTime } from "../../simulation/utils/assignedAirportTime";

const OPERATIONS_MAP_FOCUS_KEY = "tasf.operations.mapFocus";
const REALTIME_STATUS_POLL_MS = 5_000;

function elapsedOperationTimeMs(data: SimulationData | null) {
  if (!data?.realStartedAt) return 0;
  const startedAt = Date.parse(data.realStartedAt);
  if (!Number.isFinite(startedAt)) return 0;
  const finishedAt = data.realFinishedAt ? Date.parse(data.realFinishedAt) : Number.NaN;
  return Math.max(0, (Number.isFinite(finishedAt) ? finishedAt : Date.now()) - startedAt);
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
  const assignedAirportTime = useAssignedAirportTime();
  const [initialMapFocus] = useState(() => readMapFocus(OPERATIONS_MAP_FOCUS_KEY));
  const [data, setData] = useState<SimulationData | null>(null);
  const [, setLoading] = useState(true);
  const [cancelling, setCancelling] = useState(false);
  const [error, setError] = useState("");
  const [notice, setNotice] = useState("");
  const [flightToCancel, setFlightToCancel] = useState("");
  const [selectedAirport, setSelectedAirport] = useState<string | null>(
    () => initialMapFocus?.type === "airport" ? initialMapFocus.id : null
  );
  const [selectedFlightId, setSelectedFlightId] = useState<string | null>(
    () => initialMapFocus?.type === "flight" ? initialMapFocus.id : null
  );
  const [selectedShipment, setSelectedShipment] = useState<Shipment | null>(null);
  const [mapFocusTarget, setMapFocusTarget] = useState<MapFocusTarget | null>(null);
  const [leftPanelOpen, setLeftPanelOpen] = useState(true);
  const [rightPanelOpen, setRightPanelOpen] = useState(true);
  const [shipmentHistoryHours, setShipmentHistoryHours] = useState(1);
  const [operationDayToggling, setOperationDayToggling] = useState(false);
  const [operationSummaryOpen, setOperationSummaryOpen] = useState(false);
  const [operationSummaryData, setOperationSummaryData] = useState<SimulationData | null>(null);
  const [operationSummaryRealTimeMs, setOperationSummaryRealTimeMs] = useState(0);
  const [now, setNow] = useState(new Date());
  const focusTokenRef = useRef(0);
  const refreshingRef = useRef(false);
  const selectedShipmentId = selectedShipment?.id ?? null;

  const operationalMinute = useMemo(() => {
    if (!data) return 0;
    const visualStart = data.visualStartTick ?? data.tick ?? 0;
    const visualEnd = data.visualEndTick ?? Math.min(visualStart + 1, data.maxTick || visualStart + 1);
    const visualStartedAt = data.visualStartedAt || data.realFinishedAt;

    if (!visualStartedAt || visualEnd <= visualStart) return data.tick || visualStart;

    const intervalMs =
      data.realtimeExecutionIntervalMs ??
      data.planningIntervalMs ??
      data.batchIntervalMs ??
      120_000;
    const elapsedMs = Math.max(0, now.getTime() - new Date(visualStartedAt).getTime());
    const progress = Math.min(elapsedMs / Math.max(1, intervalMs), 1);
    return Math.min(
      data.maxTick || Number.POSITIVE_INFINITY,
      visualStart + (visualEnd - visualStart) * progress
    );
  }, [data, now]);

  const restoreMapFocus = useCallback((payload: SimulationData) => {
    const stored = readMapFocus(OPERATIONS_MAP_FOCUS_KEY);

    if (stored?.type === "airport" && payload.airports.some((airport) => airport.code === stored.id)) {
      setSelectedAirport(stored.id);
      setSelectedFlightId(null);
      setSelectedShipment(null);
      setMapFocusTarget({ type: "airport", id: stored.id, token: ++focusTokenRef.current });
      return;
    }

    if (stored?.type === "flight" && payload.flights.some((flight) => flight.id === stored.id)) {
      setSelectedAirport(null);
      setSelectedFlightId(stored.id);
      setSelectedShipment(null);
      setMapFocusTarget({ type: "flight", id: stored.id, token: ++focusTokenRef.current });
      return;
    }

    writeMapFocus(OPERATIONS_MAP_FOCUS_KEY, null);
    setSelectedAirport(null);
    setSelectedFlightId(null);
    setSelectedShipment(null);
    setMapFocusTarget(null);
  }, []);

  useEffect(() => {
    const timer = window.setInterval(() => setNow(new Date()), 1000);
    return () => window.clearInterval(timer);
  }, []);

  useEffect(() => {
    let ignore = false;

    void getCurrentRealtimeSessionRequest()
      .then((payload) => {
        if (ignore) return;
        if (payload) {
          setData(payload);
          restoreMapFocus(payload);
          return;
        }
        return startRealtimeSessionRequest().then((created) => {
          if (ignore) return;
          setData(created);
          restoreMapFocus(created);
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
  }, [restoreMapFocus]);

  useEffect(() => {
    if (!data?.simulationId || data.status === "COMPLETED" || data.status === "PAUSED") return;

    let cancelled = false;

    const refreshCurrentState = () => {
      if (refreshingRef.current) return;
      refreshingRef.current = true;
      void getCurrentRealtimeSessionRequest()
        .then((payload) => {
          if (cancelled || !payload) return;
          setData(payload);
          if (selectedAirport && !payload.airports.some((airport) => airport.code === selectedAirport)) {
            setSelectedAirport(null);
            writeMapFocus(OPERATIONS_MAP_FOCUS_KEY, null);
          }
          if (selectedFlightId && !payload.flights.some((flight) => flight.id === selectedFlightId)) {
            setSelectedFlightId(null);
            writeMapFocus(OPERATIONS_MAP_FOCUS_KEY, null);
          }
          if (selectedShipmentId && !payload.shipments.some((shipment) => shipment.id === selectedShipmentId)) {
            setSelectedShipment(null);
          }
        })
        .catch((err) => {
          if (!cancelled) {
            setError(err instanceof Error ? err.message : "No se pudo sincronizar la operacion.");
          }
        })
        .finally(() => {
          refreshingRef.current = false;
        });
    };

    refreshCurrentState();
    const timer = window.setInterval(refreshCurrentState, REALTIME_STATUS_POLL_MS);
    return () => {
      cancelled = true;
      window.clearInterval(timer);
    };
  }, [data?.simulationId, data?.status, selectedAirport, selectedFlightId, selectedShipmentId]);

  const airportLoads = useMemo<AirportLoads>(() => {
    return computeAirportLoads(data, operationalMinute);
  }, [data, operationalMinute]);
  const activeFlights = useMemo(
    () => computeActiveFlights(data, operationalMinute),
    [data, operationalMinute]
  );
  const activeFlightIds = useMemo(
    () => new Set(activeFlights.map(f => f.id)),
    [activeFlights]
  )
  const visibleShipments = useMemo(
    () => {
      const historyMinutes = shipmentHistoryHours * 60;
      return (data?.shipments ?? []).filter(
        (shipment) =>
          shipment.requestMinute <= operationalMinute &&
          (!shipment.planned || operationalMinute <= shipment.estimatedArrival + historyMinutes)
      );
    },
    [data, operationalMinute, shipmentHistoryHours]
  );
  const selected = data?.airports.find((airport) => airport.code === selectedAirport);
  const displayGmtOffset = assignedAirportTime?.gmtOffset;
  const operationsClosed = data?.status === "PAUSED";

  const clearMapSelection = useCallback(() => {
    setSelectedAirport(null);
    setSelectedFlightId(null);
    setSelectedShipment(null);
    setMapFocusTarget(null);
    writeMapFocus(OPERATIONS_MAP_FOCUS_KEY, null);
  }, []);

  const focusAirport = (code: string) => {
    if (selectedAirport === code) {
      clearMapSelection();
      return;
    }

    setSelectedAirport(code);
    setSelectedFlightId(null);
    setSelectedShipment(null);
    setMapFocusTarget({ type: "airport", id: code, token: ++focusTokenRef.current });
    writeMapFocus(OPERATIONS_MAP_FOCUS_KEY, { type: "airport", id: code });
  };

  const focusFlight = (id: string) => {
    if (selectedFlightId === id) {
      clearMapSelection();
      return;
    }

    setSelectedAirport(null);
    setSelectedFlightId(id);
    setSelectedShipment(null);
    setMapFocusTarget({ type: "flight", id, token: ++focusTokenRef.current });
    writeMapFocus(OPERATIONS_MAP_FOCUS_KEY, { type: "flight", id });
  };

  const focusShipment = (shipment: Shipment) => {
    if (selectedShipment?.id === shipment.id) {
      clearMapSelection();
      return;
    }

    setSelectedAirport(null);
    setSelectedFlightId(null);
    setSelectedShipment(shipment);
    setMapFocusTarget({ type: "shipment", id: shipment.id, token: ++focusTokenRef.current });
    writeMapFocus(OPERATIONS_MAP_FOCUS_KEY, null);
  };

  const toggleOperationDay = async () => {
    if (!data?.simulationId || operationDayToggling) return;

    const closingOperations = !operationsClosed;
    setOperationDayToggling(true);
    setError("");
    setNotice("");
    try {
      const updated = await pauseRealtimeSessionRequest(data.simulationId, !operationsClosed);
      setData(updated);
      if (closingOperations) {
        setOperationSummaryData(updated);
        setOperationSummaryRealTimeMs(updated.runtimeMs ?? elapsedOperationTimeMs(updated));
        setOperationSummaryOpen(true);
      } else {
        setOperationSummaryOpen(false);
        setOperationSummaryData(null);
        setOperationSummaryRealTimeMs(0);
      }
      setNotice(
        operationsClosed
          ? "Operaciones del dia abiertas. La planificacion vuelve a ejecutarse."
          : "Operaciones del dia finalizadas. La planificacion queda detenida."
      );
    } catch (err) {
      setError(err instanceof Error ? err.message : "No se pudo actualizar el estado de operaciones.");
    } finally {
      setOperationDayToggling(false);
    }
  };

  const handleOperationSummaryOpenChange = (open: boolean) => {
    setOperationSummaryOpen(open);
    if (!open) {
      setOperationSummaryData(null);
      setOperationSummaryRealTimeMs(0);
    }
  };

  const cancelFlight = async () => {
    if (!data?.simulationId || !flightToCancel.trim()) return;
    setCancelling(true);
    setError("");
    setNotice("");
    try {
      const updated = await cancelRealtimeFlightRequest(data.simulationId, flightToCancel.trim());
      setData(updated);
      setFlightToCancel("");
      setNotice("Cancelación registrada. Se aplicará en la siguiente ejecución de Tiempo real.");
    } catch (err) {
      setError(err instanceof Error ? err.message : "No se pudo cancelar el vuelo.");
    } finally {
      setCancelling(false);
    }
  };

  return (
    <div className="app-shell">
      <Navbar />

      <main
        className={[
          "workspace",
          !leftPanelOpen ? "sim-left-collapsed" : "",
          !rightPanelOpen ? "sim-right-collapsed" : "",
        ].filter(Boolean).join(" ")}
      >
        {leftPanelOpen ? (
        <aside className="side-panel">
          <button
            type="button"
            className="panel-collapse-button panel-collapse-button-left"
            onClick={() => setLeftPanelOpen(false)}
            aria-label="Ocultar panel izquierdo"
            title="Ocultar panel izquierdo"
          >
            <PanelLeftClose size={18} />
          </button>
          <section className="panel section">
            <h2>Panel de control</h2>
            <div className="metrics current-time-metrics">
              <div className="metric">
                <span>Fecha actual</span>
                <strong>{formatDateOnly(now, displayGmtOffset)}</strong>
              </div>
              <div className="metric">
                <span>Hora actual</span>
                <strong>{formatClock(now, displayGmtOffset)}</strong>
              </div>
            </div>
            <div className="control-grid">
              <div className="field">
                <label>Cancelar vuelo</label>
                <input
                  type="text"
                  placeholder="flight_code (ej: SKBO-SEQM-20260101-0334-0001)"
                  value={flightToCancel}
                  onChange={(event) => setFlightToCancel(event.target.value)}
                  disabled={!data?.simulationId || cancelling}
                />
              </div>

              <button
                className="primary"
                onClick={cancelFlight}
                disabled={!data?.simulationId || !flightToCancel || cancelling}
              >
                {cancelling ? "Registrando..." : "Registrar cancelacion"}
              </button>
              {error && <div className="error">{error}</div>}
              {notice && <div className="success">{notice}</div>}
            </div>
          </section>

          <GlobalIndicators
            data={data}
            currentMinute={operationalMinute}
            samplingIntervalMinutes={data?.planningIntervalMinutes}
          />

          <CapacityLegend />

          <section className="panel section">
            <h3>Indicadores</h3>
            {data ? (
              <LiveMetrics data={data} airportLoads={airportLoads} />
            ) : (
              <div className="empty-state">Esperando datos de tiempo real.</div>
            )}
          </section>

          <section className="panel section operations-day-actions">
            <button
              type="button"
              className={operationsClosed ? "primary" : "danger"}
              onClick={toggleOperationDay}
              disabled={!data?.simulationId || operationDayToggling}
            >
              {operationDayToggling
                ? "Actualizando..."
                : operationsClosed
                  ? "Abrir operaciones del dia"
                  : "Finalizar operaciones del dia"}
            </button>
          </section>
        </aside>
        ) : (
        <aside className="panel-rail panel-rail-left" aria-label="Panel izquierdo oculto">
          <button
            type="button"
            className="panel-toggle-button"
            onClick={() => setLeftPanelOpen(true)}
            aria-label="Mostrar panel izquierdo"
            title="Mostrar panel izquierdo"
          >
            <PanelLeftOpen size={18} />
          </button>
        </aside>
        )}

        <section className="panel map-panel live-map-panel">
          <MapStage
            data={data}
            activeFlights={activeFlights}
            airportLoads={airportLoads}
            selectedAirport={selectedAirport}
            selectedFlightId={selectedFlightId}
            selectedShipment={selectedShipment}
            focusTarget={mapFocusTarget}
            displayGmtOffset={displayGmtOffset}
            onSelectAirport={focusAirport}
            onSelectFlight={focusFlight}
            onClearSelection={clearMapSelection}
          />
        </section>

        {rightPanelOpen ? (
        <aside className="right-panel">
          <div className="panel section panel-runtime">
            <span>Panel de operaciones</span>
            <button
              type="button"
              className="panel-collapse-button panel-collapse-button-right"
              onClick={() => setRightPanelOpen(false)}
              aria-label="Ocultar panel derecho"
              title="Ocultar panel derecho"
            >
              <PanelRightClose size={18} />
            </button>
          </div>
          <section className="panel section">
            <div className="section-header">
              <h3>{selected ? `${selected.code} - ${selected.city}` : "Aeropuerto"}</h3>
              {selected && (
                <button
                  type="button"
                  className="icon-button section-close"
                  onClick={clearMapSelection}
                  aria-label="Quitar aeropuerto seleccionado"
                  title="Quitar seleccion"
                >
                  x
                </button>
              )}
            </div>
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
            <h3>Vuelos</h3>
            <FlightsTable
              flights={data?.flights || []}
              activeFlightIds={activeFlightIds}
              shipments={visibleShipments}
              simMinute={operationalMinute}
              selectedFlightId={selectedFlightId}
              onSelectFlight={focusFlight}
              displayGmtOffset={displayGmtOffset}
            />
          </section>

          <section className="panel section">
            <div style={{ display: "flex", alignItems: "center", justifyContent: "space-between", gap: "1rem", flexWrap: "wrap" }}>
              <h3>Envios</h3>
              <label className="text-sm" style={{ display: "flex", alignItems: "center", gap: "0.5rem", margin: 0 }}>
                Mostrar finalizados hace
                <input
                  type="number"
                  min={0}
                  max={24}
                  step={1}
                  value={shipmentHistoryHours}
                  onChange={(e) => setShipmentHistoryHours(Number(e.target.value))}
                  style={{ width: "4.5rem" }}
                />
                h
              </label>
            </div>
            <ShipmentsTable
              shipments={visibleShipments}
              flights={data?.flights || []}
              data={data}
              simMinute={operationalMinute}
              displayGmtOffset={displayGmtOffset}
              selectedShipmentId={selectedShipmentId}
              onSelectShipment={focusShipment}
              onSelectFlight={focusFlight}
            />
          </section>

          <section className="panel section">
            <h3>Aeropuertos criticos</h3>
            {data ? (
              <AirportsTable
                airports={data.airports}
                loads={airportLoads}
                flights={data.flights}
                shipments={visibleShipments}
                simMinute={operationalMinute}
                selectedAirport={selectedAirport}
                displayGmtOffset={displayGmtOffset}
                onSelectAirport={focusAirport}
              />
            ) : (
              <div className="empty-state">Sin datos.</div>
            )}
          </section>
        </aside>
        ) : (
        <aside className="panel-rail panel-rail-right" aria-label="Panel derecho oculto">
          <button
            type="button"
            className="panel-toggle-button"
            onClick={() => setRightPanelOpen(true)}
            aria-label="Mostrar panel derecho"
            title="Mostrar panel derecho"
          >
            <PanelRightOpen size={18} />
          </button>
        </aside>
        )}
      </main>
      <SimulationResultModal
        open={operationSummaryOpen}
        onOpenChange={handleOperationSummaryOpenChange}
        data={operationSummaryData}
        realTimeMs={operationSummaryRealTimeMs}
        title="Resumen de operaciones del dia"
        description=""
        footerLabel="Cerrar"
        closeOnOutside={false}
        showHeaderClose={false}
      />
    </div>
  );
};
