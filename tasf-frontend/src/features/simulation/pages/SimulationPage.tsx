import { useEffect, useMemo, useState, useRef, useCallback } from "react"
import { PanelLeftClose, PanelLeftOpen, PanelRightClose, PanelRightOpen } from "lucide-react"
import {
  getAirportsRequest,
  getFlightsRequest,
  getCurrentBatchSimulationRequest,
  startBatchSimulationRequest,
  advanceBatchSimulationRequest,
  cancelBatchFlightRequest,
} from "../../../api/simulationApi"
import { Navbar } from "../../../shared/components/Navbar/Navbar"
import { AirportDetail } from "../components/AirportDetail"
import { AirportsTable } from "../components/AirportsTable"
import { FlightsTable } from "../components/FlightsTable"
import { ShipmentsTable } from "../components/ShipmentsTable"
import { Metrics } from "../components/Metrics"
import MapStage from "../components/simulation/map/MapStage"
import { Timeline } from "../components/Timeline"
import { Topbar } from "../components/Topbar"
import { useSimulationPlayer } from "../hooks/useSimulationPlayer"
import type { Airport, Flight, SimulationData } from "../types"
import { DEFAULT_START_DATE, SIMULATION_DAYS } from "../utils/constants"
import { computeActiveFlights, computeAirportLoads } from "../utils/calculations"
import { formatRealTime } from "../utils/timeUtils"
import { SimulationResultModal } from "../components/SimulationResultModal"

/**
 * SimulationPage — Simulación por lotes sincronizada con el backend.
 *
 * Flujo por lote:
 *   1. El frontend llama a /advance (steps = BATCH_MINUTES = 180).
 *   2. El backend ejecuta el ALNS y responde con el nuevo estado (tick avanzado).
 *   3. El frontend recibe los datos y arranca animateBatch(tickAnterior, tickNuevo).
 *   4. requestAnimationFrame interpola simMinute durante BATCH_DURATION_MS (2 min).
 *      → La UI muestra vuelos, cargas, etc. evolucionando suavemente.
 *   5. Al terminar la animación, onBatchCompleteRef dispara → vuelve al paso 1.
 *
 * Cancelación de vuelo:
 *   – Pausa la animación y llama a /cancel-flight.
 *   – El backend replanifica y devuelve el estado actualizado.
 *   – Se reanuda la animación desde el tick actual.
 */
export function SimulationPage() {
  const [startDate, setStartDate]     = useState(DEFAULT_START_DATE)
  const [startTime, setStartTime]     = useState("00:00")
  const [days]                        = useState(SIMULATION_DAYS)
  const [data, setData]               = useState<SimulationData | null>(null)
  const [airportCatalog, setAirportCatalog] = useState<Airport[]>([])
  const [flightCatalog, setFlightCatalog] = useState<Flight[]>([])
  const [loading, setLoading]         = useState(false)   // cargando primer lote
  const [loadingAirports, setLoadingAirports] = useState(false)
  const [loadingFlights, setLoadingFlights] = useState(false)
  const [fetching, setFetching]       = useState(false)   // cargando lote intermedio
  const [cancelling, setCancelling]   = useState(false)
  const [error, setError]             = useState("")
  const [notice, setNotice]           = useState("")
  const [flightToCancel, setFlightToCancel] = useState("")
  const [selectedAirport, setSelectedAirport] = useState<string | null>(null)
  const [reportDismissed, setReportDismissed] = useState(false)
  const [now, setNow]                 = useState(new Date())
  const [realTimeMs, setRealTimeMs]   = useState(0)
  const [leftPanelOpen, setLeftPanelOpen] = useState(true)
  const [rightPanelOpen, setRightPanelOpen] = useState(true)

  const accumulatedRef  = useRef(0)
  const playStartRef    = useRef<number | null>(null)
  // Guarda el tick que tenía la animación ANTES de pedir el lote siguiente,
  // para pasar el "from" correcto a animateBatch cuando llega la respuesta.
  const prevTickRef     = useRef(0)
  // true mientras la animación del lote está corriendo
  const animatingRef    = useRef(false)

  const maxMinute = data?.maxTick ?? days * 1440

  const {
    simMinute,
    setSimMinute,
    playing,
    setPlaying,
    animateBatch,
    reset,
    onBatchCompleteRef,
    BATCH_MINUTES,
  } = useSimulationPlayer(maxMinute)

  const showReport = Boolean(
    data?.status === "COMPLETED" && simMinute >= maxMinute && !reportDismissed
  )

  // ── Reloj de pared ─────────────────────────────────────────────────────────
  useEffect(() => {
    const t = window.setInterval(() => setNow(new Date()), 1000)
    return () => window.clearInterval(t)
  }, [])

  useEffect(() => {
    const timers = [0, 120, 320].map((delay) =>
      window.setTimeout(() => window.dispatchEvent(new Event("resize")), delay)
    )
    return () => timers.forEach(window.clearTimeout)
  }, [leftPanelOpen, rightPanelOpen])

  useEffect(() => {
    let cancelled = false
    setLoadingAirports(true)
    setLoadingFlights(true)

    Promise.all([getAirportsRequest(), getFlightsRequest()])
      .then(([airports, flights]) => {
        if (cancelled) return
        setAirportCatalog(airports)
        setFlightCatalog(flights)
        setSelectedAirport((current) => current || airports[0]?.code || null)
      })
      .catch(() => {
        if (!cancelled) setError("No se pudieron leer los aeropuertos o vuelos desde la BD.")
      })
      .finally(() => {
        if (!cancelled) {
          setLoadingAirports(false)
          setLoadingFlights(false)
        }
      })

    return () => {
      cancelled = true
    }
  }, [])

  // ── Contador de tiempo real de ejecución ───────────────────────────────────
  useEffect(() => {
    if (playing) {
      if (!playStartRef.current) playStartRef.current = Date.now()
      const t = window.setInterval(() => {
        setRealTimeMs(accumulatedRef.current + (Date.now() - (playStartRef.current ?? 0)))
      }, 100)
      return () => window.clearInterval(t)
    } else {
      if (playStartRef.current) {
        accumulatedRef.current += Date.now() - playStartRef.current
        playStartRef.current = null
        setRealTimeMs(accumulatedRef.current)
      }
    }
  }, [playing])

  // ── Pedir siguiente lote ───────────────────────────────────────────────────
  const fetchNextBatch = useCallback(async (sessionId: string, fromTick: number) => {
    if (fetching) return
    setFetching(true)
    setError("")
    try {
      const payload = await advanceBatchSimulationRequest(sessionId, BATCH_MINUTES, fromTick)
      setData(payload)
      setNotice(payload.message || "")

      const toTick = payload.tick ?? fromTick

      if (toTick > fromTick) {
        // Hay datos nuevos: animar desde el tick anterior hasta el nuevo
        animatingRef.current = true
        animateBatch(fromTick, Math.min(toTick, maxMinute))
      } else {
        // No avanzó (ya completado)
        animatingRef.current = false
        setPlaying(false)
      }
    } catch (err) {
      setError(err instanceof Error ? err.message : "Error al actualizar la simulación.")
      setPlaying(false)
      animatingRef.current = false
    } finally {
      setFetching(false)
    }
  }, [fetching, BATCH_MINUTES, animateBatch, maxMinute, setPlaying])

  // ── Callback que dispara el hook cuando termina la animación de un lote ────
  useEffect(() => {
    onBatchCompleteRef.current = () => {
      animatingRef.current = false

      if (!data?.simulationId || data.status === "COMPLETED" || !playing) return

      // Guardar el tick actual como "from" del próximo lote
      const currentTick = data.tick ?? 0
      prevTickRef.current = currentTick

      if (currentTick >= maxMinute) {
        setPlaying(false)
        return
      }

      // Pedir el siguiente lote inmediatamente — el backend responde en ~ALNS_time,
      // mientras tanto la UI queda estática brevemente mostrando el estado del lote.
      void fetchNextBatch(data.simulationId, currentTick)
    }
  }, [data, playing, maxMinute, setPlaying, fetchNextBatch, onBatchCompleteRef])

  useEffect(() => {
    let cancelled = false
    void getCurrentBatchSimulationRequest()
      .then((payload) => {
        if (cancelled || !payload) return
        setData(payload)
        setSelectedAirport(payload.airports[0]?.code || airportCatalog[0]?.code || null)
        setSimMinute(payload.tick ?? payload.startOffsetMinutes ?? 0)
        setPlaying(payload.status !== "COMPLETED")
      })
      .catch(() => {
        // No hay simulación compartida todavía o el backend no está disponible.
      })
    return () => {
      cancelled = true
    }
  }, [airportCatalog, setPlaying, setSimMinute])

  useEffect(() => {
    if (!data?.simulationId || data.status === "COMPLETED") return
    const timer = window.setInterval(() => {
      if (fetching || animatingRef.current) return
      void getCurrentBatchSimulationRequest()
        .then((payload) => {
          if (!payload) return
          const previousTick = data.tick ?? data.startOffsetMinutes ?? 0
          const nextTick = payload.tick ?? previousTick
          setData(payload)
          if (nextTick > previousTick) {
            animatingRef.current = true
            animateBatch(previousTick, Math.min(nextTick, maxMinute))
          } else {
            setSimMinute(nextTick)
          }
        })
        .catch(() => {})
    }, 5_000)
    return () => window.clearInterval(timer)
  }, [data, fetching, animateBatch, maxMinute, setSimMinute])

  // ── Iniciar simulación ─────────────────────────────────────────────────────
  const runSimulation = async () => {
    setLoading(true)
    setError("")
    setNotice("")
    setReportDismissed(false)
    accumulatedRef.current = 0
    playStartRef.current   = null
    prevTickRef.current    = 0
    animatingRef.current   = false
    setRealTimeMs(0)
    reset()

    try {
      // 1. Crear la sesión (tick = 0, status = RUNNING, sin datos aún)
      const initial = await startBatchSimulationRequest(startDate, days, startTime)
      setData(initial)
      setSimMinute(initial.tick ?? initial.startOffsetMinutes ?? 0)
      setSelectedAirport(initial.airports[0]?.code || airportCatalog[0]?.code || null)
      setPlaying(true)

      // 2. Pedir inmediatamente el primer lote
      await fetchNextBatch(initial.simulationId!, initial.tick ?? initial.startOffsetMinutes ?? 0)
    } catch (err) {
      setError(err instanceof Error ? err.message : "No se pudo iniciar la simulación.")
      setPlaying(false)
    } finally {
      setLoading(false)
    }
  }

  // ── Cancelar vuelo futuro ─────────────────────────────────────────────────
  const cancelFlight = async () => {
    const id = flightToCancel.trim()
    if (!id) { setError("Ingresa el codigo de un vuelo."); return }
    if (!data?.simulationId) { setError("Primero inicia una simulación."); return }

    // Pausar la animación durante la cancelación
    setPlaying(false)
    animatingRef.current = false
    setCancelling(true)
    setError("")
    setNotice("")

    try {
      const updated = await cancelBatchFlightRequest(data.simulationId, id)
      setFlightToCancel("")
      setData(updated)
      setSelectedAirport(updated.airports[0]?.code || airportCatalog[0]?.code || null)
      setNotice(`Vuelo ${id} cancelado. Replanificacion aplicada sobre el estado actual.`)
      setPlaying(true)
    } catch (err) {
      setError(err instanceof Error ? err.message : "No se pudo cancelar el vuelo y replanificar.")
      setPlaying(false)
    } finally {
      setCancelling(false)
    }
  }

  // ── Derivados de la UI ─────────────────────────────────────────────────────
  const displayData = useMemo(
    () => data ?? catalogSimulationData(airportCatalog, flightCatalog),
    [airportCatalog, data, flightCatalog]
  )
  const airportLoads = useMemo(
    () => computeAirportLoads(displayData, simMinute),
    [displayData, simMinute]
  )
  const activeFlights = useMemo(
    () => computeActiveFlights(displayData, simMinute),
    [displayData, simMinute]
  )
  const visibleShipments = useMemo(
    () =>
      (displayData.shipments ?? []).filter(
        (s) => s.requestMinute <= simMinute && simMinute <= s.estimatedArrival + 60
      ),
    [displayData, simMinute]
  )
  const selected = displayData.airports.find((a) => a.code === selectedAirport)

  const handleAirportStatusUpdated = (code: string, active: boolean, status: string) => {
    const updateAirport = (airport: Airport) =>
      airport.code === code
        ? { ...airport, active, operationalStatus: status }
        : airport

    setAirportCatalog((airports) => airports.map(updateAirport))
    setData((current) => current
      ? { ...current, airports: current.airports.map(updateAirport) }
      : current
    )
  }

  const handleReset = () => {
    setPlaying(false)
    setSimMinute(0)
    setData(null)
    setError("")
    setNotice("")
    setFlightToCancel("")
    setSelectedAirport(null)
    setReportDismissed(false)
    accumulatedRef.current = 0
    playStartRef.current   = null
    setRealTimeMs(0)
    animatingRef.current   = false
    reset()
  }

  return (
    <div className="app-shell">
      <Navbar />
      <Topbar data={data} now={now} simMinute={simMinute} />

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
          <SimulationControls
            days={days}
            error={error}
            notice={notice}
            flightToCancel={flightToCancel}
            hasSimulation={Boolean(data?.simulationId)}
            loading={loading}
            fetching={fetching}
            playing={playing}
            cancelling={cancelling}
            simMinute={simMinute}
            startDate={startDate}
            startTime={startTime}
            onCancelFlight={cancelFlight}
            onFlightToCancelChange={setFlightToCancel}
            onPause={() => setPlaying(false)}
            onPlay={() => {
              // Reanudar: pedir el siguiente lote si no hay animación en curso
              if (!animatingRef.current && data?.simulationId && data.status !== "COMPLETED") {
                setPlaying(true)
                void fetchNextBatch(data.simulationId, data.tick ?? 0)
              } else {
                setPlaying(true)
              }
            }}
            onReset={handleReset}
            onRunSimulation={runSimulation}
            onStartDateChange={setStartDate}
            onStartTimeChange={setStartTime}
          />
          <CapacityLegend />
          <section className="panel section">
            <h3>Indicadores</h3>
            {data ? (
              <Metrics data={data} />
            ) : (
              <div className="empty-state">Ejecuta el simulador para ver métricas.</div>
            )}
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

        <section className="panel map-panel">
          <MapStage
            data={displayData}
            activeFlights={activeFlights}
            airportLoads={airportLoads}
            selectedAirport={selectedAirport}
            onSelectAirport={setSelectedAirport}
          />
          <Timeline
            simMinute={simMinute}
            maxMinute={maxMinute}
            setSimMinute={setSimMinute}
            data={data}
            startDate={startDate}
          />
        </section>

        {rightPanelOpen ? (
        <aside className="right-panel">
          <div className="panel section panel-runtime">
            <span>Tiempo de ejecución: <strong>{formatRealTime(realTimeMs)}</strong></span>
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
            <h3>{selected ? `${selected.code} - ${selected.city}` : "Aeropuerto"}</h3>
            {loadingAirports && <div className="empty-state">Cargando aeropuertos...</div>}
            {selected && (
              <AirportDetail
                airport={selected}
                load={airportLoads[selected.code] || 0}
                onStatusUpdated={handleAirportStatusUpdated}
              />
            )}
          </section>
          <section className="panel section">
            <h3>Vuelos activos</h3>
            {loadingFlights && <div className="empty-state">Cargando vuelos...</div>}
            <FlightsTable flights={activeFlights} />
          </section>
          <section className="panel section">
            <h3>Envíos</h3>
            {loadingFlights && <div className="empty-state">Cargando envíos...</div>}
            <ShipmentsTable shipments={visibleShipments} simMinute={simMinute}/>
          </section>
          <section className="panel section">
            <h3>Aeropuertos críticos</h3>
            {displayData.airports.length ? (
              <AirportsTable airports={displayData.airports} loads={airportLoads} />
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
        open={showReport}
        onOpenChange={(open) => setReportDismissed(!open)}
        data={data}
        realTimeMs={realTimeMs}
      />
    </div>
  )
}

// ── Controles ─────────────────────────────────────────────────────────────────

type SimulationControlsProps = {
  days: number
  error: string
  notice: string
  flightToCancel: string
  hasSimulation: boolean
  loading: boolean
  fetching: boolean
  playing: boolean
  cancelling: boolean
  simMinute: number
  startDate: string
  startTime: string
  onCancelFlight: () => void
  onFlightToCancelChange: (id: string) => void
  onPause: () => void
  onPlay: () => void
  onReset: () => void
  onRunSimulation: () => void
  onStartDateChange: (date: string) => void
  onStartTimeChange: (time: string) => void
}

function SimulationControls({
  days, error, notice, flightToCancel, hasSimulation,
  loading, fetching, playing, cancelling, simMinute, startDate, startTime,
  onCancelFlight, onFlightToCancelChange,
  onPause, onPlay, onReset, onRunSimulation, onStartDateChange, onStartTimeChange,
}: SimulationControlsProps) {
  const busy = loading || fetching || cancelling

  return (
    <section className="panel section">
      <h2>Simulación 5 días</h2>
      <div className="control-grid">
        <div className="field">
          <label>Fecha inicial</label>
          <input
            type="date"
            value={startDate}
            onChange={(e) => onStartDateChange(e.target.value)}
            disabled={busy || playing}
          />
        </div>

        <div className="field">
          <label>Hora inicial</label>
          <input
            type="time"
            value={startTime}
            onChange={(e) => onStartTimeChange(e.target.value)}
            disabled={busy || playing}
          />
        </div>

        <div className="metric">
          <span>Días de simulación</span>
          <strong>{days}</strong>
          <span>duración fija</span>
        </div>

        <button className="primary" onClick={onRunSimulation} disabled={busy || playing}>
          {loading ? "Iniciando..." : "Ejecutar Simulación"}
        </button>

        {/* Indicador de estado */}
        <div className="metric">
          <span>Minuto simulado</span>
          <strong>{Math.floor(simMinute)}</strong>
          <span>
            {loading     ? "iniciando sesión..." :
             fetching    ? "actualizando simulación..." :
             cancelling  ? "replanificando..." :
             playing     ? "reproduciendo simulación..." :
                           "pausado"}
          </span>
        </div>

        {/* Cancelar vuelo futuro */}
        <div className="field">
          <label>Cancelar vuelo futuro</label>
          <input
            type="text"
            placeholder="flight_code (ej: SKBO-SEQM-20260101-0334-0001)"
            value={flightToCancel}
            onChange={(e) => onFlightToCancelChange(e.target.value)}
            disabled={!hasSimulation || busy}
          />
        </div>
        <button
          className="primary"
          onClick={onCancelFlight}
          disabled={!hasSimulation || busy}
        >
          {cancelling ? "Replanificando..." : "Cancelar y replanificar"}
        </button>

        {notice && <div className="success">{notice}</div>}
        {error  && <div className="error">{error}</div>}

        <div className="segmented">
          <button onClick={onPlay}  className={playing ? "active" : ""} disabled={busy}>Play</button>
          <button onClick={onPause} disabled={busy}>Pausa</button>
          <button onClick={onReset} disabled={busy}>Reset</button>
        </div>
      </div>
    </section>
  )
}

function CapacityLegend() {
  return (
    <section className="panel section">
      <h3>Colores por capacidad</h3>
      <div className="legend">
        <div className="legend-row"><span className="dot green"></span>Menor a 70%</div>
        <div className="legend-row"><span className="dot yellow"></span>Desde 70% hasta menor a 90%</div>
        <div className="legend-row"><span className="dot red"></span>90% o más</div>
      </div>
    </section>
  )
}

function catalogSimulationData(airports: Airport[], flights: Flight[]): SimulationData {
  const start = `${DEFAULT_START_DATE}T00:00:00`
  return {
    scenario: "CATALOG",
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
  }
}
