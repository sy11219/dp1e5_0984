import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { PanelLeftClose, PanelLeftOpen, PanelRightClose, PanelRightOpen } from "lucide-react";
import {
  cancelRealtimeFlightRequest,
  getCurrentRealtimeSessionRequest,
  pauseRealtimeSessionRequest,
  restartRealtimeSessionRequest,
  startRealtimeSessionRequest,
} from "../../../api/simulationApi";
import { Navbar } from "../../../shared/components/Navbar/Navbar";
import { AirportsTable } from "../../simulation/components/panel/tables/AirportsTable";
import { CapacityLegend } from "../../simulation/components/panel/CapacityLegend";
import { FlightsTable } from "../../simulation/components/panel/tables/FlightsTable";
import { GlobalIndicators } from "../../simulation/components/panel/GlobalIndicators";
import { ShipmentsTable } from "../../simulation/components/panel/tables/ShipmentsTable";
import { SimulationResultModal } from "../../simulation/components/general/SimulationResultModal";
import { OperationsStatusCards } from "../../simulation/components/general/Topbar";
import MapStage, { type MapFocusTarget } from "../../../shared/components/map/MapStage";
import { DraggableMapOverlay } from "../../simulation/components/general/DraggableMapOverlay";
import type { AirportLoads, Shipment, SimulationData } from "../../simulation/types";
import { capacityStatus, computeActiveFlights, computeAirportLoadMetrics } from "../../simulation/utils/calculations";
import { readMapFocus, writeMapFocus } from "../../simulation/utils/mapFocusStorage";
import type { CapacityStatus } from "../../simulation/types";
import {
  formatClock,
  formatDateOnly,
  percent,
} from "../../simulation/utils/formatters";

// Color filter type
type ColorFilter = "Todos" | CapacityStatus;
type RightPanelSection = "flights" | "shipments" | "airports";

const OPERATIONS_MAP_FOCUS_KEY = "tasf.operations.mapFocus";
const REALTIME_STATUS_POLL_MS = 5_000;

function elapsedOperationTimeMs(data: SimulationData | null, fallbackNow = Date.now()) {
  if (!data?.realStartedAt) return 0;
  const startedAt = Date.parse(data.realStartedAt);
  if (!Number.isFinite(startedAt)) return 0;
  const finishedAt = data.realFinishedAt ? Date.parse(data.realFinishedAt) : Number.NaN;
  return Math.max(0, (Number.isFinite(finishedAt) ? finishedAt : fallbackNow) - startedAt);
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
        <span>pendientes de asignación</span>
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
  const [mapResetViewToken, setMapResetViewToken] = useState(0);
  const [leftPanelOpen, setLeftPanelOpen] = useState(false);
  const [rightPanelOpen, setRightPanelOpen] = useState(false);
  const [openRightPanelSection, setOpenRightPanelSection] = useState<RightPanelSection | null>(null);
  const [operationDayToggling, setOperationDayToggling] = useState(false);
  const [restartingOperationDay, setRestartingOperationDay] = useState(false);
  const [operationSummaryOpen, setOperationSummaryOpen] = useState(false);
  const [flightColorFilter, setFlightColorFilter] = useState<ColorFilter>("Todos");
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
          setError(err instanceof Error ? err.message : "No se pudo conectar la operación.");
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
            setError(err instanceof Error ? err.message : "No se pudo sincronizar la operación.");
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

  const { loads: airportLoads, peakLoads: airportPeakLoads } = useMemo(() => {
    const operationDayStart = Math.floor(Math.max(0, operationalMinute) / 1440) * 1440;
    return computeAirportLoadMetrics(data, operationalMinute, operationDayStart);
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
    () => [...(data?.shipments ?? [])].sort((a, b) => a.requestMinute - b.requestMinute),
    [data?.shipments]
  );
  const displayGmtOffset = undefined;
  const operationsClosed = data?.status === "PAUSED";
  const operationRealTimeMs = useMemo(
    () => elapsedOperationTimeMs(data, now.getTime()),
    [data, now]
  );

  const clearMapSelection = useCallback((options?: { resetView?: boolean }) => {
    setSelectedAirport(null);
    setSelectedFlightId(null);
    setSelectedShipment(null);
    setMapFocusTarget(null);
    if (options?.resetView) setMapResetViewToken((token) => token + 1);
    writeMapFocus(OPERATIONS_MAP_FOCUS_KEY, null);
  }, []);

  const focusAirport = (code: string) => {
    if (selectedAirport === code) {
      clearMapSelection({ resetView: true });
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
      clearMapSelection({ resetView: true });
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
      clearMapSelection({ resetView: true });
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
          ? "Operaciones del día abiertas. La planificación vuelve a ejecutarse."
          : "Operaciones del día finalizadas. La planificación queda detenida."
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

  const restartOperationDay = async () => {
    if (restartingOperationDay) return;
    setRestartingOperationDay(true);
    setError("");
    setNotice("");
    try {
      const updated = await restartRealtimeSessionRequest(data?.days);
      setData(updated);
      restoreMapFocus(updated);
      setNotice("Nueva jornada iniciada con el catálogo vigente.");
    } catch (err) {
      setError(err instanceof Error ? err.message : "No se pudo reiniciar las operaciones.");
    } finally {
      setRestartingOperationDay(false);
    }
  };

  const toggleRightPanelSection = (section: RightPanelSection) => {
    setOpenRightPanelSection((current) => current === section ? null : section);
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
      setNotice("Cancelación registrada. Aplicando replanificación al siguiente batch.");
    } catch (err) {
      setError(err instanceof Error ? err.message : "No se pudo cancelar el vuelo.");
    } finally {
      setCancelling(false);
    }
  };

  return (
    <div className="app-shell simulation-shell">
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
          <section className="panel section simulation-side-nav">
            <Navbar />
          </section>
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
                  placeholder="ID: SKBO-VIDP-0005"
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
                {cancelling ? "Registrando..." : "Registrar cancelación"}
              </button>
              {error && <div className="error">{error}</div>}
              {notice && <div className="success">{notice}</div>}
            </div>
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
                  ? "Abrir operaciones del día"
                  : "Finalizar operaciones del día"}
            </button>
            <button
              type="button"
              className="ghost"
              onClick={restartOperationDay}
              disabled={restartingOperationDay}
              title="Inicia una nueva jornada de operaciones con los datos vigentes del catálogo"
            >
              {restartingOperationDay ? "Iniciando jornada..." : "Iniciar nueva jornada"}
            </button>
          </section>

          <section className="panel section">
            <h3>Indicadores</h3>
            {data ? (
              <LiveMetrics data={data} airportLoads={airportLoads} />
            ) : (
              <div className="empty-state">Esperando datos de tiempo real.</div>
            )}
          </section>

          <CapacityLegend />
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
            airportPeakLoads={airportPeakLoads}
            selectedAirport={selectedAirport}
            selectedFlightId={selectedFlightId}
            selectedShipment={selectedShipment}
            focusTarget={mapFocusTarget}
            resetViewToken={mapResetViewToken}
            displayGmtOffset={displayGmtOffset}
            onSelectAirport={focusAirport}
            onSelectFlight={focusFlight}
            onClearSelection={clearMapSelection}
            flightColorFilter={flightColorFilter}
          >
            <DraggableMapOverlay anchor="bottom-right" initialX={18} initialY={76} className="map-global-indicators-overlay">
              <GlobalIndicators
                data={data}
                currentMinute={operationalMinute}
                samplingIntervalMinutes={data?.planningIntervalMinutes}
              />
            </DraggableMapOverlay>
            <OperationsStatusCards
              data={data}
              operationalMinute={operationalMinute}
              realNow={now}
              realDurationMs={operationRealTimeMs}
              displayGmtOffset={displayGmtOffset}
            />
          </MapStage>
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
          <section className="panel section collapsible-section">
            <button
              type="button"
              className="collapsible-trigger"
              onClick={() => toggleRightPanelSection("flights")}
              aria-expanded={openRightPanelSection === "flights"}
            >
              <span>Vuelos</span>
              <strong>{openRightPanelSection === "flights" ? "-" : "+"}</strong>
            </button>
            {openRightPanelSection === "flights" && (
              <div className="collapsible-content">
                <FlightsTable
                  flights={flightColorFilter === "Todos" ? data?.flights || [] : (data?.flights || []).filter((f) => capacityStatus(f.utilization) === flightColorFilter)}
                  activeFlightIds={activeFlightIds}
                  shipments={visibleShipments}
                  data={data}
                  selectedFlightId={selectedFlightId}
                  onSelectFlight={focusFlight}
                  displayGmtOffset={displayGmtOffset}
                  colorFilter={flightColorFilter}
                  onColorFilterChange={setFlightColorFilter}
                  hideColorFilter={false}
                />
              </div>
            )}
          </section>

          <section className="panel section collapsible-section">
            <button
              type="button"
              className="collapsible-trigger"
              onClick={() => toggleRightPanelSection("shipments")}
              aria-expanded={openRightPanelSection === "shipments"}
            >
              <span>Envíos</span>
              <strong>{openRightPanelSection === "shipments" ? "-" : "+"}</strong>
            </button>
            {openRightPanelSection === "shipments" && (
              <div className="collapsible-content">
                <div className="list-toolbar">
                  <h3>Envíos</h3>
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
              </div>
            )}
          </section>

          <section className="panel section collapsible-section">
            <button
              type="button"
              className="collapsible-trigger"
              onClick={() => toggleRightPanelSection("airports")}
              aria-expanded={openRightPanelSection === "airports"}
            >
              <span>Aeropuertos</span>
              <strong>{openRightPanelSection === "airports" ? "-" : "+"}</strong>
            </button>
            {openRightPanelSection === "airports" && (
              <div className="collapsible-content">
            <h3>Aeropuertos</h3>
            {data ? (
              <AirportsTable
                airports={data.airports}
                loads={airportLoads}
                flights={data.flights}
                shipments={visibleShipments}
                simMinute={operationalMinute}
                data={data}
                selectedAirport={selectedAirport}
                displayGmtOffset={displayGmtOffset}
                onSelectAirport={focusAirport}
              />
            ) : (
              <div className="empty-state">Sin datos.</div>
            )}
              </div>
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
        title="Resumen de operaciones del día"
        description=""
        footerLabel="Cerrar"
        closeOnOutside={false}
        showHeaderClose={false}
      />
    </div>
  );
};
