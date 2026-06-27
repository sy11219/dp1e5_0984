import { useEffect, useMemo, useState, useRef, useCallback } from "react"
import { PanelLeftClose, PanelLeftOpen, PanelRightClose, PanelRightOpen } from "lucide-react"
import {
  getAirportsRequest,
  getFlightsRequest,
  getCurrentBatchSimulationRequest,
  startBatchSimulationRequest,
  advanceBatchSimulationRequest,
  stopBatchSimulationRequest,
  pauseBatchSimulationRequest,
  cancelBatchFlightRequest,
  ownsBatchSimulation,
} from "../../../api/simulationApi"
import { Navbar } from "../../../shared/components/Navbar/Navbar"
import { AirportDetail } from "../components/AirportDetail"
import { AirportsTable } from "../components/AirportsTable"
import { CapacityLegend } from "../components/CapacityLegend"
import { FlightsTable } from "../components/FlightsTable"
import { GlobalIndicators } from "../components/GlobalIndicators"
import { ShipmentsTable } from "../components/ShipmentsTable"
import { Metrics } from "../components/Metrics"
import MapStage, { type MapFocusTarget } from "../components/simulation/map/MapStage"
import { Timeline } from "../components/Timeline"
import { Topbar } from "../components/Topbar"
import { useSimulationPlayer } from "../hooks/useSimulationPlayer"
import type { Airport, Flight, SimulationData } from "../types"
import { DEFAULT_START_DATE, DEFAULT_START_TIME, SIMULATION_DAYS } from "../utils/constants"
import { computeActiveFlights, computeAirportLoads } from "../utils/calculations"
import { SimulationResultModal } from "../components/SimulationResultModal"
import { readMapFocus, writeMapFocus } from "../utils/mapFocusStorage"

const BATCH_SIMULATION_PAUSED_KEY = "tasf.simulation5d.paused"
const BATCH_SIMULATION_STOPPED_KEY = "tasf.simulation5d.stoppedSessionId"
const SIMULATION_MAP_FOCUS_KEY = "tasf.simulation5d.mapFocus"
const SIMULATION_REPORT_DISMISSED_PREFIX = "tasf.simulation5d.reportDismissed."

function setBatchSimulationPaused(paused: boolean) {
  try {
    if (paused) {
      window.localStorage.setItem(BATCH_SIMULATION_PAUSED_KEY, "true")
    } else {
      window.localStorage.removeItem(BATCH_SIMULATION_PAUSED_KEY)
    }
  } catch {
    // Local storage can be unavailable in some browser privacy modes.
  }
}

function markBatchSimulationStopped(data: SimulationData | null) {
  if (!data?.simulationId) return

  try {
    window.localStorage.setItem(BATCH_SIMULATION_STOPPED_KEY, data.simulationId)
  } catch {
    // Ignore storage failures; the backend stop still clears the shared session.
  }
}

function clearBatchSimulationStopped() {
  try {
    window.localStorage.removeItem(BATCH_SIMULATION_STOPPED_KEY)
  } catch {
    // Ignore storage failures.
  }
}

function wasBatchSimulationStopped(data: SimulationData | null) {
  if (!data?.simulationId) return false

  try {
    return window.localStorage.getItem(BATCH_SIMULATION_STOPPED_KEY) === data.simulationId
  } catch {
    return false
  }
}

function elapsedRealTimeMs(data: SimulationData | null) {
  if (!data?.realStartedAt) return 0

  const startedAt = Date.parse(data.realStartedAt)
  if (!Number.isFinite(startedAt)) return 0

  const finishedAt =
    data.status === "COMPLETED" && data.realFinishedAt
      ? Date.parse(data.realFinishedAt)
      : Date.now()

  return Math.max(0, (Number.isFinite(finishedAt) ? finishedAt : Date.now()) - startedAt)
}

function simulationStartControls(data: SimulationData | null) {
  if (!data?.simulationStartDateTime) return null

  const date = new Date(data.simulationStartDateTime)
  if (!Number.isFinite(date.getTime())) return null

  const pad = (value: number) => String(value).padStart(2, "0")
  return {
    startDate: `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}`,
    startTime: `${pad(date.getHours())}:${pad(date.getMinutes())}`,
  }
}

function reportDismissedKey(data: SimulationData | null) {
  return data?.simulationId ? `${SIMULATION_REPORT_DISMISSED_PREFIX}${data.simulationId}` : null
}

function wasFinalReportDismissed(data: SimulationData | null) {
  const key = reportDismissedKey(data)
  if (!key) return false

  try {
    return window.localStorage.getItem(key) === "true"
  } catch {
    return false
  }
}

function markFinalReportDismissed(data: SimulationData | null) {
  const key = reportDismissedKey(data)
  if (!key) return

  try {
    window.localStorage.setItem(key, "true")
  } catch {
    // Ignore storage failures; the in-memory flag still prevents duplicate modals.
  }
}

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
  const [initialMapFocus] = useState(() => readMapFocus(SIMULATION_MAP_FOCUS_KEY))
  const [startDate, setStartDate]     = useState(DEFAULT_START_DATE)
  const [startTime, setStartTime]     = useState(DEFAULT_START_TIME)
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
  const [selectedAirport, setSelectedAirport] = useState<string | null>(
    () => initialMapFocus?.type === "airport" ? initialMapFocus.id : null
  )
  const [selectedFlightId, setSelectedFlightId] = useState<string | null>(
    () => initialMapFocus?.type === "flight" ? initialMapFocus.id : null
  )
  const [mapFocusTarget, setMapFocusTarget] = useState<MapFocusTarget | null>(null)
  const [reportDismissed, setReportDismissed] = useState(false)
  const [stopSummaryOpen, setStopSummaryOpen] = useState(false)
  const [stoppedSummaryData, setStoppedSummaryData] = useState<SimulationData | null>(null)
  const [stoppedSummaryRealTimeMs, setStoppedSummaryRealTimeMs] = useState(0)
  const [realTimeMs, setRealTimeMs]   = useState(0)
  const [leftPanelOpen, setLeftPanelOpen] = useState(true)
  const [rightPanelOpen, setRightPanelOpen] = useState(true)

  // Guarda el tick que tenía la animación ANTES de pedir el lote siguiente,
  // para pasar el "from" correcto a animateBatch cuando llega la respuesta.
  const prevTickRef     = useRef(0)
  const focusTokenRef   = useRef(0)
  // true mientras la animación del lote está corriendo
  const animatingRef    = useRef(false)

  const maxMinute = data?.maxTick ?? days * 1440

  const {
    simMinute,
    setSimMinute,
    playing,
    setPlaying,
    animateBatch,
    stopAnimation,
    reset,
    onBatchCompleteRef,
    BATCH_MINUTES,
  } = useSimulationPlayer(maxMinute)

  const showReport = Boolean(
    data?.status === "COMPLETED" &&
    simMinute >= maxMinute &&
    !reportDismissed &&
    !wasFinalReportDismissed(data)
  )
  const modalOpen = showReport || stopSummaryOpen

  const syncControlFieldsFromSimulation = useCallback((payload: SimulationData | null) => {
    const controls = simulationStartControls(payload)
    if (!controls) return
    setStartDate(controls.startDate)
    setStartTime(controls.startTime)
  }, [])

  const syncSharedVisualWindow = useCallback((payload: SimulationData) => {
    const visualStart =
      payload.visualStartTick ??
      payload.lastBatchStart ??
      payload.startOffsetMinutes ??
      0
    const visualEnd = payload.visualEndTick ?? payload.tick ?? visualStart
    const cap = payload.maxTick ?? maxMinute

    if (payload.status === "PAUSED") {
      animatingRef.current = false
      stopAnimation()
      setSimMinute(visualEnd)
      return
    }

    if (payload.status !== "COMPLETED" && payload.visualStartedAt && visualEnd > visualStart) {
      animatingRef.current = true
      animateBatch(
        visualStart,
        Math.min(visualEnd, cap),
        payload.visualStartedAt,
        payload.planningIntervalMs ?? payload.batchIntervalMs
      )
      return
    }

    animatingRef.current = false
    setSimMinute(payload.tick ?? payload.startOffsetMinutes ?? 0)
  }, [animateBatch, maxMinute, setSimMinute, stopAnimation])

  const restoreMapFocus = useCallback((payload: SimulationData) => {
    const stored = readMapFocus(SIMULATION_MAP_FOCUS_KEY)

    if (stored?.type === "airport" && payload.airports.some((airport) => airport.code === stored.id)) {
      setSelectedAirport(stored.id)
      setSelectedFlightId(null)
      setMapFocusTarget({ type: "airport", id: stored.id, token: ++focusTokenRef.current })
      return
    }

    if (stored?.type === "flight" && payload.flights.some((flight) => flight.id === stored.id)) {
      setSelectedAirport(null)
      setSelectedFlightId(stored.id)
      setMapFocusTarget({ type: "flight", id: stored.id, token: ++focusTokenRef.current })
      return
    }

    writeMapFocus(SIMULATION_MAP_FOCUS_KEY, null)
    setSelectedAirport(null)
    setSelectedFlightId(null)
    setMapFocusTarget(null)
  }, [])

  // ── Reloj de pared ─────────────────────────────────────────────────────────
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
        setSelectedAirport((current) => {
          const stored = readMapFocus(SIMULATION_MAP_FOCUS_KEY)
          if (stored?.type === "airport" && airports.some((airport) => airport.code === stored.id)) {
            return stored.id
          }
          if (current && airports.some((airport) => airport.code === current)) return current
          return null
        })
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
    setRealTimeMs(elapsedRealTimeMs(data))

    if (!playing || !data?.realStartedAt || data.status === "COMPLETED") return

    const t = window.setInterval(() => {
      setRealTimeMs(elapsedRealTimeMs(data))
    }, 1000)

    return () => window.clearInterval(t)
  }, [data, playing])

  // ── Pedir siguiente lote ───────────────────────────────────────────────────
  const fetchNextBatch = useCallback(async (sessionId: string, fromTick: number) => {
    if (fetching) return
    if (data?.simulationId === sessionId && !ownsBatchSimulation(data)) return
    setFetching(true)
    setError("")
    try {
      const stepMinutes = data?.planningWindowMinutes ?? data?.batchMinutes ?? BATCH_MINUTES
      const payload = await advanceBatchSimulationRequest(sessionId, stepMinutes, fromTick)
      setData(payload)
      setNotice(payload.message || "")

      const toTick = payload.tick ?? fromTick
      const payloadMaxMinute = payload.maxTick ?? maxMinute

      if (toTick > fromTick) {
        // Hay datos nuevos: animar desde el tick anterior hasta el nuevo
        animatingRef.current = true
        animateBatch(
          fromTick,
          Math.min(toTick, payloadMaxMinute),
          payload.visualStartedAt,
          payload.planningIntervalMs ?? payload.batchIntervalMs
        )
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
  }, [fetching, data, BATCH_MINUTES, animateBatch, maxMinute, setPlaying])

  // ── Callback que dispara el hook cuando termina la animación de un lote ────
  useEffect(() => {
    onBatchCompleteRef.current = () => {
      animatingRef.current = false

      if (!data?.simulationId || data.status === "COMPLETED" || !playing || !ownsBatchSimulation(data)) return

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
        if (wasBatchSimulationStopped(payload)) {
          setBatchSimulationPaused(true)
          return
        }
        if (payload.status === "COMPLETED" && wasFinalReportDismissed(payload)) {
          setReportDismissed(true)
          return
        }
        syncControlFieldsFromSimulation(payload)
        setData(payload)
        setReportDismissed(wasFinalReportDismissed(payload))
        restoreMapFocus(payload)
        syncSharedVisualWindow(payload)
        setPlaying(payload.status === "RUNNING")
      })
      .catch(() => {
        // No hay simulación compartida todavía o el backend no está disponible.
      })
    return () => {
      cancelled = true
    }
  }, [restoreMapFocus, setPlaying, syncControlFieldsFromSimulation, syncSharedVisualWindow])

  useEffect(() => {
    if (!data?.simulationId || data.status === "COMPLETED") return
    const timer = window.setInterval(() => {
      if (fetching) return
      void getCurrentBatchSimulationRequest()
        .then((payload) => {
          if (!payload) {
            stopAnimation()
            setPlaying(false)
            setData(null)
            setSelectedAirport(null)
            setSelectedFlightId(null)
            setMapFocusTarget(null)
            writeMapFocus(SIMULATION_MAP_FOCUS_KEY, null)
            animatingRef.current = false
            return
          }
          if (wasBatchSimulationStopped(payload)) {
            setBatchSimulationPaused(true)
            return
          }
          const sessionChanged = payload.simulationId !== data.simulationId
          const previousTick = data.tick ?? data.startOffsetMinutes ?? 0
          const nextTick = payload.tick ?? previousTick
          const payloadMaxMinute = payload.maxTick ?? maxMinute
          syncControlFieldsFromSimulation(payload)
          setData(payload)
          if (sessionChanged) {
            restoreMapFocus(payload)
          }
          setPlaying(payload.status === "RUNNING")
          if (sessionChanged) {
            syncSharedVisualWindow(payload)
          } else if (payload.status === "PAUSED") {
            syncSharedVisualWindow(payload)
          } else if (!animatingRef.current && nextTick > previousTick) {
            animatingRef.current = true
            animateBatch(
              previousTick,
              Math.min(nextTick, payloadMaxMinute),
              payload.visualStartedAt,
              payload.planningIntervalMs ?? payload.batchIntervalMs
            )
          } else {
            syncSharedVisualWindow(payload)
          }
        })
        .catch(() => {})
    }, 5_000)
    return () => window.clearInterval(timer)
  }, [data, fetching, animateBatch, maxMinute, restoreMapFocus, setPlaying, stopAnimation, syncControlFieldsFromSimulation, syncSharedVisualWindow])

  // ── Iniciar simulación ─────────────────────────────────────────────────────
  const runSimulation = async () => {
    setLoading(true)
    setError("")
    setNotice("")
    setReportDismissed(false)
    clearBatchSimulationStopped()
    writeMapFocus(SIMULATION_MAP_FOCUS_KEY, null)
    setBatchSimulationPaused(false)
    prevTickRef.current    = 0
    animatingRef.current   = false
    setRealTimeMs(0)
    reset()

    try {
      // 1. Crear la sesión (tick = 0, status = RUNNING, sin datos aún)
      const initial = await startBatchSimulationRequest(startDate, days, startTime, data?.simulationId)
      syncControlFieldsFromSimulation(initial)
      setData(initial)
      setSimMinute(initial.tick ?? initial.startOffsetMinutes ?? 0)
      setSelectedAirport(null)
      setSelectedFlightId(null)
      setMapFocusTarget(null)
      setPlaying(true)

      // 2. Pedir el primer lote sin mantener bloqueado el estado "Iniciando".
      void fetchNextBatch(initial.simulationId!, initial.tick ?? initial.startOffsetMinutes ?? 0)
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
      setSelectedAirport(null)
      setSelectedFlightId(null)
      setMapFocusTarget(null)
      writeMapFocus(SIMULATION_MAP_FOCUS_KEY, null)
      setNotice(`Vuelo ${id} cancelado. Replanificacion aplicada sobre el estado actual.`)
      setPlaying(updated.status === "RUNNING")
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
  const activeFlightIds = useMemo(
    () => new Set(activeFlights.map(f => f.id)),
    [activeFlights]
  )
  const visibleShipments = useMemo(
    () =>
      (displayData.shipments ?? [])
        .filter(
          (s) =>
            s.requestMinute <= simMinute &&
            (!s.planned || simMinute <= s.estimatedArrival + 60)
        )
        .sort((a, b) => a.estimatedArrival - b.estimatedArrival),
    [displayData, simMinute]
  );
  const selected = displayData.airports.find((a) => a.code === selectedAirport)
  const controlsBusy = loading || fetching || cancelling
  const ownsCurrentSimulation = ownsBatchSimulation(data)
  const canControlSimulation = !data?.simulationId || ownsCurrentSimulation

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

  const clearMapSelection = useCallback(() => {
    setSelectedAirport(null)
    setSelectedFlightId(null)
    setMapFocusTarget(null)
    writeMapFocus(SIMULATION_MAP_FOCUS_KEY, null)
  }, [])

  const focusAirport = (code: string) => {
    // Si es el mismo aeropuerto, deseleccionar
    if (selectedAirport === code) {
      clearMapSelection()
    } else {
      setSelectedAirport(code)
      setSelectedFlightId(null)
      setMapFocusTarget({ type: "airport", id: code, token: ++focusTokenRef.current })
      writeMapFocus(SIMULATION_MAP_FOCUS_KEY, { type: "airport", id: code })
    }
  }

  const focusFlight = (id: string) => {
    // Si es el mismo vuelo, deseleccionar
    if (selectedFlightId === id) {
      clearMapSelection()
    } else {
      setSelectedAirport(null)
      setSelectedFlightId(id)
      setMapFocusTarget({ type: "flight", id, token: ++focusTokenRef.current })
      writeMapFocus(SIMULATION_MAP_FOCUS_KEY, { type: "flight", id })
    }
  }

  const clearSimulationView = (options?: { preserveReportDismissed?: boolean }) => {
    setPlaying(false)
    setSimMinute(0)
    setData(null)
    setError("")
    setNotice("")
    setFlightToCancel("")
    setSelectedAirport(null)
    setSelectedFlightId(null)
    setMapFocusTarget(null)
    writeMapFocus(SIMULATION_MAP_FOCUS_KEY, null)
    if (!options?.preserveReportDismissed) setReportDismissed(false)
    setBatchSimulationPaused(true)
    prevTickRef.current    = 0
    setRealTimeMs(0)
    animatingRef.current   = false
    reset()
  }

  const handlePause = async () => {
    if (!data?.simulationId || !ownsBatchSimulation(data)) return
    setBatchSimulationPaused(true)
    setPlaying(false)
    stopAnimation()
    animatingRef.current = false
    try {
      const updated = await pauseBatchSimulationRequest(data.simulationId, true)
      setData(updated)
      syncSharedVisualWindow(updated)
    } catch (err) {
      setError(err instanceof Error ? err.message : "No se pudo pausar la simulacion.")
    }
  }

  const handleRestart = async () => {
    setBatchSimulationPaused(false)
    setPlaying(false)
    animatingRef.current = false
    await runSimulation()
  }

  const handleStop = () => {
    if (!data) return

    const elapsed = realTimeMs || elapsedRealTimeMs(data)
    markBatchSimulationStopped(data)
    setBatchSimulationPaused(true)
    setRealTimeMs(elapsed)
    setPlaying(false)
    animatingRef.current = false
    setStoppedSummaryData(data)
    setStoppedSummaryRealTimeMs(elapsed)
    setStopSummaryOpen(true)
    if (data.simulationId) {
      void stopBatchSimulationRequest(data.simulationId).catch(() => {
        // The local stopped marker still prevents this browser from restoring it.
      })
    }
  }

  const handleStopSummaryOpenChange = (open: boolean) => {
    setStopSummaryOpen(open)
    if (!open) {
      setStoppedSummaryData(null)
      clearSimulationView()
    }
  }

  const handleFinalReportOpenChange = (open: boolean) => {
    if (open) return

    markFinalReportDismissed(data)
    setReportDismissed(true)
    clearSimulationView({ preserveReportDismissed: true })
  }

  return (
    <div className="app-shell">
      <Navbar />
      <Topbar data={data} simMinute={simMinute} durationMs={realTimeMs} />

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
            error={error}
            notice={notice}
            flightToCancel={flightToCancel}
            hasSimulation={Boolean(data?.simulationId)}
            loading={loading}
            fetching={fetching}
            playing={playing}
            cancelling={cancelling}
            canControlSimulation={canControlSimulation}
            startDate={startDate}
            startTime={startTime}
            onCancelFlight={cancelFlight}
            onFlightToCancelChange={setFlightToCancel}
            onPlay={() => {
              if (!canControlSimulation) return
              setBatchSimulationPaused(false)
              if (data?.simulationId && data.status === "PAUSED") {
                void (async () => {
                  try {
                    const updated = await pauseBatchSimulationRequest(data.simulationId!, false)
                    setData(updated)
                    setPlaying(updated.status === "RUNNING")
                    syncSharedVisualWindow(updated)
                    if (updated.simulationId && (updated.visualEndTick ?? updated.tick ?? 0) <= (updated.visualStartTick ?? 0)) {
                      void fetchNextBatch(updated.simulationId, updated.tick ?? 0)
                    }
                  } catch (err) {
                    setError(err instanceof Error ? err.message : "No se pudo reanudar la simulacion.")
                  }
                })()
                return
              }
              // Reanudar: pedir el siguiente lote si no hay animación en curso
              if (!animatingRef.current && data?.simulationId && data.status !== "COMPLETED") {
                setPlaying(true)
                void fetchNextBatch(data.simulationId, data.tick ?? 0)
              } else {
                setPlaying(true)
              }
            }}
            onRunSimulation={runSimulation}
            onStartDateChange={setStartDate}
            onStartTimeChange={setStartTime}
          />
          <GlobalIndicators
            data={data}
            currentMinute={simMinute}
            samplingIntervalMinutes={data?.planningIntervalMinutes}
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
          {canControlSimulation && (
            <section className="panel section simulation-bottom-actions">
              <div className="segmented">
                <button onClick={handlePause} disabled={!data?.simulationId || controlsBusy || !playing}>Pausar</button>
                <button onClick={handleRestart} disabled={!data?.simulationId || controlsBusy}>Reiniciar</button>
                <button className="danger" onClick={handleStop} disabled={!data?.simulationId || controlsBusy}>Cancelar</button>
              </div>
            </section>
          )}
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
            activeFlights={modalOpen ? [] : activeFlights}
            airportLoads={airportLoads}
            selectedAirport={selectedAirport}
            selectedFlightId={selectedFlightId}
            focusTarget={mapFocusTarget}
            onSelectAirport={focusAirport}
            onSelectFlight={focusFlight}
            onClearSelection={clearMapSelection}
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
            <h3>Vuelos</h3>
            {loadingFlights && <div className="empty-state">Cargando vuelos...</div>}
            <FlightsTable
              flights={displayData.flights}
              activeFlightIds={activeFlightIds}
              selectedFlightId={selectedFlightId}
              onSelectFlight={focusFlight}
            />
          </section>
          <section className="panel section">
            <h3>Envíos</h3>
            {loadingFlights && <div className="empty-state">Cargando envíos...</div>}
            <ShipmentsTable
              shipments={visibleShipments}
              simMinute={simMinute}
              simulationId={data?.simulationId}
              refreshKey={data?.batchCount ?? data?.tick ?? 0}
              airportOptions={displayData.airports.map((airport) => airport.code)}
            />
          </section>
          <section className="panel section">
            <h3>Aeropuertos críticos</h3>
            {displayData.airports.length ? (
              <AirportsTable
                airports={displayData.airports}
                loads={airportLoads}
                flights={displayData.flights}
                shipments={displayData.shipments}
                selectedAirport={selectedAirport}
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
        open={showReport}
        onOpenChange={handleFinalReportOpenChange}
        data={data}
        realTimeMs={realTimeMs}
      />
      <SimulationResultModal
        open={stopSummaryOpen}
        onOpenChange={handleStopSummaryOpenChange}
        data={stoppedSummaryData}
        realTimeMs={stoppedSummaryRealTimeMs}
        title="Resumen de simulación actual"
        description=""
        showFooter={false}
      />
    </div>
  )
}

// ── Controles ─────────────────────────────────────────────────────────────────

type SimulationControlsProps = {
  error: string
  notice: string
  flightToCancel: string
  hasSimulation: boolean
  loading: boolean
  fetching: boolean
  playing: boolean
  cancelling: boolean
  canControlSimulation: boolean
  startDate: string
  startTime: string
  onCancelFlight: () => void
  onFlightToCancelChange: (id: string) => void
  onPlay: () => void
  onRunSimulation: () => void
  onStartDateChange: (date: string) => void
  onStartTimeChange: (time: string) => void
}

function SimulationControls({
  error, notice, flightToCancel, hasSimulation,
  loading, fetching, playing, cancelling, canControlSimulation, startDate, startTime,
  onCancelFlight, onFlightToCancelChange,
  onPlay, onRunSimulation, onStartDateChange, onStartTimeChange,
}: SimulationControlsProps) {
  const busy = loading || fetching || cancelling

  return (
    <section className="panel section">
      <h2>Panel de control</h2>
      <div className="control-grid">
        <div className="field">
          <label>Fecha inicial</label>
          <input
            type="date"
            value={startDate}
            onChange={(e) => onStartDateChange(e.target.value)}
            disabled={busy || hasSimulation || !canControlSimulation}
          />
        </div>

        <div className="field">
          <label>Hora inicial</label>
          <input
            type="time"
            value={startTime}
            onChange={(e) => onStartTimeChange(e.target.value)}
            disabled={busy || hasSimulation || !canControlSimulation}
          />
        </div>

        {canControlSimulation && (
        <button
          className="primary"
          onClick={hasSimulation && !playing ? onPlay : onRunSimulation}
          disabled={busy || (hasSimulation && playing)}
        >
          {loading ? "Iniciando..." : "Ejecutar Simulación"}
        </button>
        )}

        {/* Cancelar vuelo futuro */}
        <div className="field">
          <label>Cancelar vuelo</label>
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
          {cancelling ? "Registrando..." : "Registrar cancelación"}
        </button>

        {notice && <div className="success">{notice}</div>}
        {error  && <div className="error">{error}</div>}
      </div>
    </section>
  )
}

function catalogSimulationData(airports: Airport[], flights: Flight[]): SimulationData {
  const start = `${DEFAULT_START_DATE}T${DEFAULT_START_TIME}:00`
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
