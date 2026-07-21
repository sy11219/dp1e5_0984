import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { PanelLeftClose, PanelLeftOpen, PanelRightClose, PanelRightOpen } from "lucide-react";
import {
  advanceCollapseSimulationRequest,
  cancelCollapseSimulationRequest,
  clearCollapseSimulationRequest,
  getAirportsRequest,
  getCurrentCollapseSimulationRequest,
  getFlightsRequest,
  ownsCollapseSimulation,
  pauseCollapseSimulationRequest,
  startCollapseSimulationRequest,
} from "../../../api/simulationApi";
import { Navbar } from "../../../shared/components/Navbar/Navbar";
import MapStage, { type MapFocusTarget } from "../../../shared/components/map/MapStage";
import { AirportsTable } from "../../simulation/components/panel/tables/AirportsTable";
import { CapacityLegend } from "../../simulation/components/panel/CapacityLegend";
import { FlightsTable } from "../../simulation/components/panel/tables/FlightsTable";
import { GlobalIndicators } from "../../simulation/components/panel/GlobalIndicators";
import { ShipmentsTable } from "../../simulation/components/panel/tables/ShipmentsTable";
import { SimulationResultModal } from "../../simulation/components/general/SimulationResultModal";
import { SimulationStatusCards } from "../../simulation/components/general/Topbar";
import { DraggableMapOverlay } from "../../simulation/components/general/DraggableMapOverlay";
import { useSimulationPlayer } from "../../simulation/hooks/useSimulationPlayer";
import type { Airport, CapacityStatus, Flight, Shipment, SimulationData } from "../../simulation/types";
import { capacityStatus, computeActiveFlights, computeAirportLoadMetrics } from "../../simulation/utils/calculations";
import { DEFAULT_START_TIME } from "../../simulation/utils/constants";
import { readMapFocus, writeMapFocus } from "../../simulation/utils/mapFocusStorage";

const COLLAPSE_START_DATE = "2026-01-02";
const COLLAPSE_MAX_DAYS = 1100;
const POLL_MS = 2_000;
const MAP_FOCUS_KEY = "tasf.collapse.mapFocus";

type ColorFilter = "Todos" | CapacityStatus;
type RightPanelSection = "flights" | "shipments" | "airports";

function isTerminal(data: SimulationData | null | undefined) {
  return data?.status === "COLLAPSED" || data?.status === "CANCELLED" || data?.status === "COMPLETED";
}
function elapsedRealTimeMs(data: SimulationData | null) {
  if (!data?.realStartedAt) return 0;
  const started = Date.parse(data.realStartedAt);
  if (!Number.isFinite(started)) return 0;
  if (!isTerminal(data)) return Math.max(0, Date.now() - started);
  const finished = Date.parse(data.realFinishedAt);
  const terminalAt = Number.isFinite(finished) ? Math.min(Date.now(), finished) : Date.now();
  return Math.max(0, terminalAt - started);
}

function controlsFromSession(data: SimulationData) {
  const start = new Date(data.simulationStartDateTime);
  if (!Number.isFinite(start.getTime())) return null;
  const pad = (value: number) => String(value).padStart(2, "0");
  return {
    date: `${start.getFullYear()}-${pad(start.getMonth() + 1)}-${pad(start.getDate())}`,
    time: `${pad(start.getHours())}:${pad(start.getMinutes())}`,
  };
}

function collapseDescription(data: SimulationData | null) {
  const collapse = data?.collapse;
  if (!collapse?.reason) return "La ejecución terminó sin registrar una condición de colapso.";
  if (collapse.reason === "WAREHOUSE_CAPACITY") {
    return `El almacén de ${collapse.airport} superó su capacidad: ${collapse.currentLoad}/${collapse.maxCapacity} maletas.`;
  }
  if (collapse.reason === "DELIVERY_DEADLINE") {
    return `El envío ${collapse.shipmentId} venció su plazo real: llegaron ${collapse.deliveredBags} de ${collapse.expectedBags} maletas a tiempo.`;
  }
  return "Se alcanzó una condición de colapso.";
}

function catalogSimulationData(airports: Airport[], flights: Flight[]): SimulationData {
  const start = `${COLLAPSE_START_DATE}T${DEFAULT_START_TIME}:00`;
  return {
    scenario: "COLAPSO",
    status: "IDLE",
    days: 0,
    tick: 0,
    maxTick: 0,
    airports,
    flights,
    shipments: [],
    airportEvents: [],
    metrics: {
      plannedShipments: 0,
      shipments: 0,
      onTimeShipments: 0,
      plannedBags: 0,
      totalBags: 0,
      usedFlights: 0,
      iterations: 0,
      fitnessFinal: 0,
      fitnessInitial: 0,
      acceptedBySa: 0,
      globalImprovements: 0,
    },
    realStartedAt: start,
    realFinishedAt: start,
    simulationStartDateTime: start,
    simulationEndDateTime: start,
    runtimeMs: 0,
  };
}

export function CollapsePage() {
  const [initialMapFocus] = useState(() => readMapFocus(MAP_FOCUS_KEY));
  const [data, setData] = useState<SimulationData | null>(null);
  const [airportCatalog, setAirportCatalog] = useState<Airport[]>([]);
  const [flightCatalog, setFlightCatalog] = useState<Flight[]>([]);
  const [startDate, setStartDate] = useState(COLLAPSE_START_DATE);
  const [startTime, setStartTime] = useState(DEFAULT_START_TIME);
  const [loading, setLoading] = useState(false);
  const [fetching, setFetching] = useState(false);
  const [loadingCatalog, setLoadingCatalog] = useState(false);
  const [error, setError] = useState("");
  const [notice, setNotice] = useState("");
  const [realTimeMs, setRealTimeMs] = useState(0);
  const [reportOpen, setReportOpen] = useState(false);
  const [leftPanelOpen, setLeftPanelOpen] = useState(false);
  const [rightPanelOpen, setRightPanelOpen] = useState(false);
  const [openRightPanelSection, setOpenRightPanelSection] = useState<RightPanelSection | null>(null);
  const [flightColorFilter, setFlightColorFilter] = useState<ColorFilter>("Todos");
  const [airportColorFilter, setAirportColorFilter] = useState<ColorFilter>("Todos");
  const [selectedAirport, setSelectedAirport] = useState<string | null>(
    () => initialMapFocus?.type === "airport" ? initialMapFocus.id : null
  );
  const [selectedFlightId, setSelectedFlightId] = useState<string | null>(
    () => initialMapFocus?.type === "flight" ? initialMapFocus.id : null
  );
  const [selectedShipment, setSelectedShipment] = useState<Shipment | null>(null);
  const [mapFocusTarget, setMapFocusTarget] = useState<MapFocusTarget | null>(null);
  const [mapResetViewToken, setMapResetViewToken] = useState(0);

  const focusTokenRef = useRef(0);
  const advancingRef = useRef(false);
  const latestDataRef = useRef<SimulationData | null>(null);
  const maxMinute = data?.maxTick ?? 0;
  const {
    simMinute,
    setSimMinute,
    playing,
    setPlaying,
    animateBatch,
    stopAnimation,
    reset,
    onBatchCompleteRef,
  } = useSimulationPlayer(maxMinute);

  const updateSession = useCallback((payload: SimulationData, options?: { animate?: boolean }) => {
    latestDataRef.current = payload;
    setData(payload);
    setNotice(payload.message || "");
    setPlaying(payload.status === "RUNNING");

    const controls = controlsFromSession(payload);
    if (controls) {
      setStartDate(controls.date);
      setStartTime(controls.time);
    }

    const start = payload.visualStartTick ?? payload.lastBatchStart ?? payload.startOffsetMinutes ?? 0;
    const end = payload.visualEndTick ?? payload.tick ?? start;
    const shouldAnimate = options?.animate !== false
      && payload.visualStartedAt
      && end > start
      && (payload.status === "RUNNING" || payload.status === "COLLAPSED");

    if (shouldAnimate) {
      animateBatch(start, end, payload.visualStartedAt, payload.planningIntervalMs ?? payload.batchIntervalMs);
    } else {
      stopAnimation();
      setSimMinute(end);
    }
  }, [animateBatch, setPlaying, setSimMinute, stopAnimation]);

  const advanceOwnedSession = useCallback(async (session: SimulationData) => {
    if (!session.simulationId || !ownsCollapseSimulation(session) || advancingRef.current) return;
    advancingRef.current = true;
    setFetching(true);
    try {
      const updated = await advanceCollapseSimulationRequest(
        session.simulationId,
        session.tick ?? session.startOffsetMinutes ?? 0
      );
      updateSession(updated);
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : "No se pudo avanzar el escenario de colapso.");
    } finally {
      advancingRef.current = false;
      setFetching(false);
    }
  }, [updateSession]);

  useEffect(() => {
    onBatchCompleteRef.current = () => {
      const session = latestDataRef.current;
      if (!session) return;
      setSimMinute(session.visualEndTick ?? session.tick ?? 0);
      if (session.status === "COLLAPSED") {
        setPlaying(false);
        setReportOpen(true);
        return;
      }
      if (session.status === "RUNNING") {
        void advanceOwnedSession(session);
      }
    };
    return () => {
      onBatchCompleteRef.current = null;
    };
  }, [advanceOwnedSession, onBatchCompleteRef, setPlaying, setSimMinute]);

  useEffect(() => {
    const timers = [0, 120, 320].map((delay) => window.setTimeout(
      () => window.dispatchEvent(new Event("resize")), delay
    ));
    return () => timers.forEach(window.clearTimeout);
  }, [leftPanelOpen, rightPanelOpen]);

  useEffect(() => {
    let cancelled = false;
    setLoadingCatalog(true);
    Promise.all([getAirportsRequest(), getFlightsRequest()])
      .then(([airports, flights]) => {
        if (cancelled) return;
        setAirportCatalog(airports);
        setFlightCatalog(flights);
      })
      .catch(() => {
        if (!cancelled) setError("No se pudieron leer los aeropuertos o vuelos desde la BD.");
      })
      .finally(() => {
        if (!cancelled) setLoadingCatalog(false);
      });
    return () => { cancelled = true; };
  }, []);

  useEffect(() => {
    let cancelled = false;
    const refresh = async () => {
      try {
        const payload = await getCurrentCollapseSimulationRequest();
        if (cancelled) return;
        if (!payload) {
          latestDataRef.current = null;
          setData(null);
          setPlaying(false);
          stopAnimation();
          return;
        }
        updateSession(payload);
      } catch {
        // Los errores de red se muestran al ejecutar una acción explícita.
      }
    };
    void refresh();
    const timer = window.setInterval(refresh, POLL_MS);
    return () => {
      cancelled = true;
      window.clearInterval(timer);
    };
  }, [setPlaying, stopAnimation, updateSession]);

  useEffect(() => {
    setRealTimeMs(elapsedRealTimeMs(data));
    if (!data || isTerminal(data) || !playing) return;
    const timer = window.setInterval(() => setRealTimeMs(elapsedRealTimeMs(data)), 1_000);
    return () => window.clearInterval(timer);
  }, [data, playing]);

  const runCollapse = async () => {
    setLoading(true);
    setError("");
    setNotice("");
    setReportOpen(false);
    stopAnimation();
    reset();
    try {
      const initial = await startCollapseSimulationRequest(startDate, COLLAPSE_MAX_DAYS, startTime, data?.simulationId);
      updateSession(initial, { animate: false });
      setSelectedAirport(null);
      setSelectedFlightId(null);
      setSelectedShipment(null);
      setMapFocusTarget(null);
      writeMapFocus(MAP_FOCUS_KEY, null);
      await advanceOwnedSession(initial);
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : "No se pudo iniciar el escenario de colapso.");
    } finally {
      setLoading(false);
    }
  };

  const handlePause = async (paused: boolean) => {
    if (!data?.simulationId || !ownsCollapseSimulation(data)) return;
    setFetching(true);
    setError("");
    try {
      const updated = await pauseCollapseSimulationRequest(data.simulationId, paused);
      updateSession(updated, { animate: !paused });
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : "No se pudo actualizar la pausa.");
    } finally {
      setFetching(false);
    }
  };

  const handleCancel = async () => {
    if (!data?.simulationId || !ownsCollapseSimulation(data)) return;
    setFetching(true);
    setError("");
    try {
      const updated = await cancelCollapseSimulationRequest(data.simulationId);
      updateSession(updated, { animate: false });
      setPlaying(false);
      setNotice("Escenario de colapso cancelado. Cualquier máquina puede limpiarlo.");
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : "No se pudo cancelar el escenario.");
    } finally {
      setFetching(false);
    }
  };

  const handleClear = async () => {
    if (!data?.simulationId || !isTerminal(data)) return;
    setFetching(true);
    setError("");
    try {
      await clearCollapseSimulationRequest(data.simulationId);
      latestDataRef.current = null;
      setData(null);
      setReportOpen(false);
      setPlaying(false);
      stopAnimation();
      reset();
      setNotice("Escenario de colapso limpiado. Está listo para una nueva ejecución.");
      setSelectedAirport(null);
      setSelectedFlightId(null);
      setSelectedShipment(null);
      setMapFocusTarget(null);
      writeMapFocus(MAP_FOCUS_KEY, null);
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : "No se pudo limpiar el escenario.");
    } finally {
      setFetching(false);
    }
  };

  const displayData = useMemo(
    () =>
      data
        ? { ...data, flights: mergeFlightCatalog(data, flightCatalog) }
        : catalogSimulationData(airportCatalog, flightCatalog),
    [airportCatalog, data, flightCatalog]
  )
  const { loads: airportLoads, peakLoads: airportPeakLoads } = useMemo(
    () => computeAirportLoadMetrics(displayData, simMinute, displayData.startOffsetMinutes ?? 0),
    [displayData, simMinute]
  );
  const activeFlights = useMemo(() => computeActiveFlights(displayData, simMinute), [displayData, simMinute]);
  const filteredActiveFlights = useMemo(
    () => flightColorFilter === "Todos"
      ? activeFlights
      : activeFlights.filter((flight) => capacityStatus(flight.utilization) === flightColorFilter),
    [activeFlights, flightColorFilter]
  );
  const activeFlightIds = useMemo(() => new Set(activeFlights.map((flight) => flight.id)), [activeFlights]);
  const visibleShipments = useMemo(
    () => [...displayData.shipments].sort((a, b) => a.requestMinute - b.requestMinute),
    [displayData.shipments]
  );
  const mapSelectedFlightId = useMemo(() => {
    if (!selectedFlightId || flightColorFilter === "Todos") return selectedFlightId;
    const selected = displayData.flights.find((flight) => flight.id === selectedFlightId);
    return selected && capacityStatus(selected.utilization) === flightColorFilter ? selectedFlightId : null;
  }, [displayData.flights, flightColorFilter, selectedFlightId]);
  const mapSelectedAirport = useMemo(() => {
    if (!selectedAirport || airportColorFilter === "Todos") return selectedAirport;
    const airport = displayData.airports.find((item) => item.code === selectedAirport);
    if (!airport) return null;
    return capacityStatus((airportLoads[airport.code] || 0) / airport.maxCapacity) === airportColorFilter
      ? selectedAirport
      : null;
  }, [airportColorFilter, airportLoads, displayData.airports, selectedAirport]);

  const clearMapSelection = useCallback((options?: { resetView?: boolean }) => {
    setSelectedAirport(null);
    setSelectedFlightId(null);
    setSelectedShipment(null);
    setMapFocusTarget(null);
    if (options?.resetView) setMapResetViewToken((current) => current + 1);
    writeMapFocus(MAP_FOCUS_KEY, null);
  }, []);

  const focusAirport = useCallback((code: string) => {
    if (selectedAirport === code) {
      clearMapSelection({ resetView: true });
      return;
    }
    setSelectedAirport(code);
    setSelectedFlightId(null);
    setSelectedShipment(null);
    setMapFocusTarget({ type: "airport", id: code, token: ++focusTokenRef.current });
    writeMapFocus(MAP_FOCUS_KEY, { type: "airport", id: code });
  }, [clearMapSelection, selectedAirport]);

  const focusFlight = useCallback((id: string) => {
    if (selectedFlightId === id) {
      clearMapSelection({ resetView: true });
      return;
    }
    setSelectedAirport(null);
    setSelectedFlightId(id);
    setSelectedShipment(null);
    setMapFocusTarget({ type: "flight", id, token: ++focusTokenRef.current });
    writeMapFocus(MAP_FOCUS_KEY, { type: "flight", id });
  }, [clearMapSelection, selectedFlightId]);

  const focusShipment = useCallback((shipment: Shipment) => {
    if (selectedShipment?.id === shipment.id) {
      clearMapSelection({ resetView: true });
      return;
    }
    setSelectedAirport(null);
    setSelectedFlightId(null);
    setSelectedShipment(shipment);
    setMapFocusTarget({ type: "shipment", id: shipment.id, token: ++focusTokenRef.current });
    writeMapFocus(MAP_FOCUS_KEY, null);
  }, [clearMapSelection, selectedShipment?.id]);

  const owner = ownsCollapseSimulation(data);
  const terminal = isTerminal(data);
  const busy = loading || fetching;
  const displayGmtOffset = undefined;
  const displayAirportLabel = "hora local de esta PC";
  const terminalTitle = data?.status === "COLLAPSED"
    ? "⚠️ COLAPSO DEL SISTEMA"
    : data?.status === "CANCELLED"
      ? "Escenario cancelado"
      : "Ejecución completada";
  const terminalDescription = data?.status === "COLLAPSED"
    ? collapseDescription(data)
    : data?.status === "CANCELLED"
      ? "La máquina dueña canceló el escenario antes de detectar un colapso."
      : "El periodo configurado terminó sin detectar un colapso.";

  return (
    <div className="app-shell simulation-shell">
      <main className={[
        "workspace",
        !leftPanelOpen ? "sim-left-collapsed" : "",
        !rightPanelOpen ? "sim-right-collapsed" : "",
      ].filter(Boolean).join(" ")}>
        {leftPanelOpen ? (
          <aside className="side-panel">
            <button type="button" className="panel-collapse-button panel-collapse-button-left" onClick={() => setLeftPanelOpen(false)} aria-label="Ocultar panel izquierdo"><PanelLeftClose size={18} /></button>
            <section className="panel section simulation-side-nav"><Navbar /></section>
            <section className="panel section">
              <h2>Escenario de colapso</h2>
              <div className="control-grid">
                <div className="field"><label>Fecha inicial</label><input type="date" value={startDate} onChange={(event) => setStartDate(event.target.value)} disabled={busy || Boolean(data?.simulationId)} /></div>
                <div className="field"><label>Hora inicial</label><input type="time" value={startTime} onChange={(event) => setStartTime(event.target.value)} disabled={busy || Boolean(data?.simulationId)} /></div>
                {(!data?.simulationId || (data.status === "PAUSED" && owner)) && <button type="button" className="primary" onClick={!data?.simulationId ? runCollapse : () => void handlePause(false)} disabled={busy}>{loading ? "Iniciando..." : data?.simulationId ? "Reanudar" : "Iniciar colapso"}</button>}
                {data?.simulationId && !terminal && !owner && <div className="empty-state">Esta máquina está observando. Solo quien inició el escenario puede pausarlo o cancelarlo.</div>}
                {notice && <div className="success">{notice}</div>}
                {error && <div className="error">{error}</div>}
              </div>
            </section>
            {data?.simulationId && (
              <section className="panel section simulation-bottom-actions">
                {terminal ? (
                  <div className="simulation-final-actions">
                    <button type="button" className="primary" onClick={() => setReportOpen(true)}>Mostrar resumen</button>
                    <button type="button" className="danger" onClick={() => void handleClear()} disabled={busy}>Limpiar escenario</button>
                  </div>
                ) : owner ? (
                  <div className="simulation-final-actions">
                    <button type="button" className="danger" onClick={() => void handleCancel()} disabled={busy}>Cancelar</button>
                  </div>
                ) : null}
              </section>
            )}
            <CapacityLegend />
          </aside>
        ) : (
          <aside className="panel-rail panel-rail-left" aria-label="Panel izquierdo oculto"><button type="button" className="panel-toggle-button" onClick={() => setLeftPanelOpen(true)} aria-label="Mostrar panel izquierdo"><PanelLeftOpen size={18} /></button></aside>
        )}

        <section className="panel map-panel">
          <MapStage data={displayData} activeFlights={filteredActiveFlights} airportLoads={airportLoads} airportPeakLoads={airportPeakLoads} airportColorFilter={airportColorFilter} selectedAirport={mapSelectedAirport} selectedFlightId={mapSelectedFlightId} selectedShipment={selectedShipment} focusTarget={mapFocusTarget} resetViewToken={mapResetViewToken} displayGmtOffset={displayGmtOffset} onSelectAirport={focusAirport} onSelectFlight={focusFlight} onClearSelection={clearMapSelection}>
            <SimulationStatusCards data={data} simMinute={simMinute} durationMs={realTimeMs} displayGmtOffset={displayGmtOffset} displayAirportLabel={displayAirportLabel} variant="collapse" />
            <DraggableMapOverlay anchor="bottom-right" initialX={18} initialY={76} className="map-global-indicators-overlay"><GlobalIndicators data={data} currentMinute={simMinute} samplingIntervalMinutes={data?.planningIntervalMinutes} /></DraggableMapOverlay>
          </MapStage>
        </section>

        {rightPanelOpen ? (
          <aside className="right-panel">
            <div className="panel section panel-runtime"><span>Panel de operaciones</span><button type="button" className="panel-collapse-button panel-collapse-button-right" onClick={() => setRightPanelOpen(false)} aria-label="Ocultar panel derecho"><PanelRightClose size={18} /></button></div>
            <section className="panel section collapsible-section">
              <button type="button" className="collapsible-trigger" onClick={() => setOpenRightPanelSection((current) => current === "flights" ? null : "flights")} aria-expanded={openRightPanelSection === "flights"}><span>Vuelos</span><strong>{openRightPanelSection === "flights" ? "-" : "+"}</strong></button>
              {openRightPanelSection === "flights" && <div className="collapsible-content">{loadingCatalog && <div className="empty-state">Cargando vuelos...</div>}<FlightsTable flights={displayData.flights} activeFlightIds={activeFlightIds} shipments={visibleShipments} data={data} selectedFlightId={selectedFlightId} onSelectFlight={focusFlight} displayGmtOffset={displayGmtOffset} colorFilter={flightColorFilter} onColorFilterChange={setFlightColorFilter} /></div>}
            </section>
            <section className="panel section collapsible-section">
              <button type="button" className="collapsible-trigger" onClick={() => setOpenRightPanelSection((current) => current === "shipments" ? null : "shipments")} aria-expanded={openRightPanelSection === "shipments"}><span>Envíos</span><strong>{openRightPanelSection === "shipments" ? "-" : "+"}</strong></button>
              {openRightPanelSection === "shipments" && <div className="collapsible-content"><ShipmentsTable shipments={visibleShipments} flights={displayData.flights} data={data} simMinute={simMinute} displayGmtOffset={displayGmtOffset} selectedShipmentId={selectedShipment?.id ?? null} onSelectShipment={focusShipment} onSelectFlight={focusFlight} /></div>}
            </section>
            <section className="panel section collapsible-section">
              <button type="button" className="collapsible-trigger" onClick={() => setOpenRightPanelSection((current) => current === "airports" ? null : "airports")} aria-expanded={openRightPanelSection === "airports"}><span>Aeropuertos</span><strong>{openRightPanelSection === "airports" ? "-" : "+"}</strong></button>
              {openRightPanelSection === "airports" && <div className="collapsible-content">{loadingCatalog && <div className="empty-state">Cargando aeropuertos...</div>}{displayData.airports.length ? <AirportsTable airports={displayData.airports} loads={airportLoads} flights={displayData.flights} shipments={visibleShipments} simMinute={simMinute} data={displayData} selectedAirport={selectedAirport} displayGmtOffset={displayGmtOffset} onSelectAirport={focusAirport} colorFilter={airportColorFilter} onColorFilterChange={setAirportColorFilter} /> : <div className="empty-state">Sin datos.</div>}</div>}
            </section>
          </aside>
        ) : (
          <aside className="panel-rail panel-rail-right" aria-label="Panel derecho oculto"><button type="button" className="panel-toggle-button" onClick={() => setRightPanelOpen(true)} aria-label="Mostrar panel derecho"><PanelRightOpen size={18} /></button></aside>
        )}
      </main>

      <SimulationResultModal
        open={reportOpen}
        onOpenChange={setReportOpen}
        data={data}
        realTimeMs={realTimeMs}
        title={terminalTitle}
        description={terminalDescription}
        footerLabel="Cerrar"
        closeOnOutside={false}
        showHeaderClose={false}
      />
    </div>
  );
}

function mergeFlightCatalog(data: SimulationData | null, catalog: Flight[]): Flight[] {
  if (!data) return catalog

  const merged = new Map<string, Flight>()
  for (const flight of data.flights ?? []) {
    merged.set(flight.id, flight)
  }
  for (const flight of catalog) {
    if (!merged.has(flight.id)) {
      merged.set(flight.id, flight)
    }
  }

  return [...merged.values()]
}
