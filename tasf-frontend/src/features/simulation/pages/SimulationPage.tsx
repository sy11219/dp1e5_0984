import { useEffect, useMemo, useState, useRef, useCallback } from "react"
import {
  startBatchSimulationRequest,
  advanceBatchSimulationRequest,
  cancelBatchFlightRequest,
} from "../../../api/simulationApi"
import { Navbar } from "../../../shared/components/Navbar/Navbar"
import { AirportDetail } from "../components/AirportDetail"
import { AirportsTable } from "../components/AirportsTable"
import { FlightsTable } from "../components/FlightsTable"
import { Metrics } from "../components/Metrics"
import MapStage from "../components/simulation/map/MapStage"
import { Timeline } from "../components/Timeline"
import { Topbar } from "../components/Topbar"
import { useSimulationPlayer } from "../hooks/useSimulationPlayer"
import type { SimulationData } from "../types"
import { DAY_OPTIONS, DEFAULT_START_DATE } from "../utils/constants"
import { computeActiveFlights, computeAirportLoads } from "../utils/calculations"
import { formatRealTime } from "../utils/timeUtils"
import { SimulationResultModal } from "../components/SimulationResultModal"

/**
 * SimulationPage — Simulación por lotes sincronizada con el backend.
 *
 * Flujo por lote:
 *   1. El frontend llama a /advance (steps = BATCH_MINUTES = 360).
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
  const [days, setDays]               = useState(3)
  const [data, setData]               = useState<SimulationData | null>(null)
  const [loading, setLoading]         = useState(false)   // cargando primer lote
  const [fetching, setFetching]       = useState(false)   // cargando lote intermedio
  const [cancelling, setCancelling]   = useState(false)
  const [error, setError]             = useState("")
  const [notice, setNotice]           = useState("")
  const [flightToCancel, setFlightToCancel] = useState("")
  const [selectedAirport, setSelectedAirport] = useState<string | null>(null)
  const [reportDismissed, setReportDismissed] = useState(false)
  const [now, setNow]                 = useState(new Date())
  const [realTimeMs, setRealTimeMs]   = useState(0)

  const accumulatedRef  = useRef(0)
  const playStartRef    = useRef<number | null>(null)
  // Guarda el tick que tenía la animación ANTES de pedir el lote siguiente,
  // para pasar el "from" correcto a animateBatch cuando llega la respuesta.
  const prevTickRef     = useRef(0)
  // true mientras la animación del lote está corriendo
  const animatingRef    = useRef(false)

  const maxMinute = days * 1440

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
    if (fetching || cancelling) return
    setFetching(true)
    setError("")
    try {
      const payload = await advanceBatchSimulationRequest(sessionId, BATCH_MINUTES)
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
      setError(err instanceof Error ? err.message : "Error al avanzar el lote.")
      setPlaying(false)
      animatingRef.current = false
    } finally {
      setFetching(false)
    }
  }, [fetching, cancelling, BATCH_MINUTES, animateBatch, maxMinute, setPlaying])

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
      const initial = await startBatchSimulationRequest(startDate, days)
      setData(initial)
      setSelectedAirport(initial.airports[0]?.code || null)
      setPlaying(true)

      // 2. Pedir inmediatamente el primer lote
      await fetchNextBatch(initial.simulationId!, 0)
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
    if (!id) { setError("Ingresa el ID de un vuelo futuro."); return }
    if (!data?.simulationId) { setError("Primero inicia una simulación."); return }

    // Pausar la animación durante la cancelación
    setPlaying(false)
    animatingRef.current = false
    setCancelling(true)
    setError("")
    setNotice("")

    try {
      const payload = await cancelBatchFlightRequest(data.simulationId, id)
      setData(payload)
      setNotice(payload.message || `Vuelo ${id} cancelado y replanificado.`)
      setFlightToCancel("")

      // Reanudar desde el tick actual
      if (payload.status !== "COMPLETED") {
        const currentTick = payload.tick ?? 0
        prevTickRef.current = currentTick
        setPlaying(true)
        void fetchNextBatch(payload.simulationId!, currentTick)
      }
    } catch (err) {
      setError(err instanceof Error ? err.message : "No se pudo cancelar el vuelo.")
    } finally {
      setCancelling(false)
    }
  }

  // ── Derivados de la UI ─────────────────────────────────────────────────────
  const airportLoads = useMemo(
    () => computeAirportLoads(data, simMinute),
    [data, simMinute]
  )
  const activeFlights = useMemo(
    () => computeActiveFlights(data, simMinute),
    [data, simMinute]
  )
  const selected = data?.airports.find((a) => a.code === selectedAirport)

  const handleDaysChange = (option: number) => {
    setDays(option)
    setSimMinute(0)
    setReportDismissed(false)
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

      <main className="workspace">
        <aside className="side-panel">
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
            onCancelFlight={cancelFlight}
            onDaysChange={handleDaysChange}
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

        <section className="panel map-panel">
          <MapStage
            data={data}
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
          />
        </section>

        <aside className="right-panel">
          <div className="panel section">
            Tiempo de ejecución: <strong>{formatRealTime(realTimeMs)}</strong>
          </div>
          <section className="panel section">
            <h3>{selected ? `${selected.code} - ${selected.city}` : "Aeropuerto"}</h3>
            {selected && (
              <AirportDetail airport={selected} load={airportLoads[selected.code] || 0} />
            )}
          </section>
          <section className="panel section">
            <h3>Vuelos activos</h3>
            <FlightsTable flights={activeFlights} />
          </section>
          <section className="panel section">
            <h3>Aeropuertos críticos</h3>
            {data ? (
              <AirportsTable airports={data.airports} loads={airportLoads} />
            ) : (
              <div className="empty-state">Sin datos.</div>
            )}
          </section>
        </aside>
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
  onCancelFlight: () => void
  onDaysChange: (days: number) => void
  onFlightToCancelChange: (id: string) => void
  onPause: () => void
  onPlay: () => void
  onReset: () => void
  onRunSimulation: () => void
  onStartDateChange: (date: string) => void
}

function SimulationControls({
  days, error, notice, flightToCancel, hasSimulation,
  loading, fetching, playing, cancelling, simMinute, startDate,
  onCancelFlight, onDaysChange, onFlightToCancelChange,
  onPause, onPlay, onReset, onRunSimulation, onStartDateChange,
}: SimulationControlsProps) {
  const busy = loading || fetching || cancelling

  return (
    <section className="panel section">
      <h2>Simulador</h2>
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
          <label>Días de simulación</label>
          <div className="segmented">
            {DAY_OPTIONS.map((opt) => (
              <button
                key={opt}
                className={opt === days ? "active" : ""}
                onClick={() => onDaysChange(opt)}
                disabled={busy || playing}
              >
                {`${opt} días`}
              </button>
            ))}
          </div>
        </div>

        <button className="primary" onClick={onRunSimulation} disabled={busy || playing}>
          {loading ? "Iniciando..." : "Ejecutar simulación"}
        </button>

        {/* Indicador de estado del lote */}
        <div className="metric">
          <span>Minuto simulado</span>
          <strong>{Math.floor(simMinute)}</strong>
          <span>
            {loading     ? "iniciando sesión..." :
             fetching    ? "▶ ejecutando ALNS del lote..." :
             cancelling  ? "replanificando..." :
             playing     ? "animando lote..." :
                           "pausado"}
          </span>
        </div>

        {/* Cancelar vuelo futuro */}
        <div className="field">
          <label>Cancelar vuelo futuro</label>
          <input
            type="text"
            placeholder="ID de vuelo (ej: LA-101-D1)"
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
