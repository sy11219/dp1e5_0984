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
import { AirportsTable } from "../components/panel/tables/AirportsTable"
import { CapacityLegend } from "../components/panel/CapacityLegend"
import { FlightsTable } from "../components/panel/tables/FlightsTable"
import { GlobalIndicators } from "../components/panel/GlobalIndicators"
import { ShipmentsTable } from "../components/panel/tables/ShipmentsTable"
import { Metrics } from "../components/general/Metrics"
import MapStage, { type MapFocusTarget } from "../../../shared/components/map/MapStage"
import { SimulationStatusCards } from "../components/general/Topbar"
import { DraggableMapOverlay } from "../components/general/DraggableMapOverlay"
import { useSimulationPlayer } from "../hooks/useSimulationPlayer"
import type { Airport, CapacityStatus, Flight, Shipment, SimulationData } from "../types"
import { DEFAULT_START_DATE, DEFAULT_START_TIME, SIMULATION_DAYS } from "../utils/constants"
import { capacityStatus, computeActiveFlights, computeAirportLoads, computeAirportPeakLoads } from "../utils/calculations"
import { SimulationResultModal } from "../components/general/SimulationResultModal"
import { readMapFocus, writeMapFocus } from "../utils/mapFocusStorage"
import { useAssignedAirportTime } from "../utils/assignedAirportTime"

const BATCH_SIMULATION_PAUSED_KEY = "tasf.simulation5d.paused"
const BATCH_SIMULATION_STOPPED_KEY = "tasf.simulation5d.stoppedSessionId"
const SIMULATION_MAP_FOCUS_KEY = "tasf.simulation5d.mapFocus"
const FINAL_SUMMARY_KEY = "tasf.simulation5d.finalSummary"
const DEFAULT_BATCH_INTERVAL_MS = 120_000
type ColorFilter = "Todos" | CapacityStatus
type RightPanelSection = "flights" | "shipments" | "airports"

type FrozenSimulationSummary = {
  simulationId?: string
  data: SimulationData
  realTimeMs: number
}

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

function simulationIdentity(data: SimulationData | null | undefined) {
  if (!data) return ""
  return data.simulationId || `${data.scenario}:${data.simulationStartDateTime}:${data.maxTick ?? data.tick ?? ""}`
}

function sameSimulation(a: SimulationData | null | undefined, b: SimulationData | null | undefined) {
  const left = simulationIdentity(a)
  const right = simulationIdentity(b)
  return Boolean(left && right && left === right)
}

function simulationSyncSignature(data: SimulationData | null | undefined) {
  if (!data) return ""

  const cancelledFlights = data.cancelledFlightIds?.join(",") ?? ""
  const metrics = data.lastPlanningMetrics ?? data.metrics
  return [
    simulationIdentity(data),
    data.status ?? "",
    data.paused ? "paused" : "active",
    data.tick ?? "",
    data.visualStartTick ?? "",
    data.visualEndTick ?? "",
    data.lastBatchStart ?? "",
    data.lastBatchEnd ?? "",
    data.visualStartedAt ?? "",
    data.batchCount ?? "",
    data.planningExecutionMs ?? "",
    data.lastBatchRuntimeMs ?? "",
    cancelledFlights,
    data.airports.length,
    data.flights.length,
    data.shipments.length,
    data.airportEvents.length,
    metrics.plannedShipments,
    metrics.plannedBags,
    metrics.usedFlights,
  ].join("|")
}

function finalVisualFinishedAtMs(data: SimulationData, fallback = Date.now()) {
  const visualStartedAt = data.visualStartedAt ? Date.parse(data.visualStartedAt) : Number.NaN
  const visualEnd = data.visualEndTick ?? data.tick ?? 0
  const maxTick = data.maxTick ?? visualEnd

  if (data.status === "COMPLETED" && visualEnd >= maxTick && Number.isFinite(visualStartedAt)) {
    return visualStartedAt + (data.planningIntervalMs ?? data.batchIntervalMs ?? DEFAULT_BATCH_INTERVAL_MS)
  }

  const realFinishedAt = data.realFinishedAt ? Date.parse(data.realFinishedAt) : Number.NaN
  if (data.status === "COMPLETED" && Number.isFinite(realFinishedAt)) return realFinishedAt

  return fallback
}

function simulationMinuteISOString(data: SimulationData, minute: number | undefined) {
  if (typeof minute !== "number" || !Number.isFinite(minute) || !data.simulationStartDateTime) {
    return undefined
  }

  const simulationStart = Date.parse(data.simulationStartDateTime)
  if (!Number.isFinite(simulationStart)) return undefined

  return new Date(
    simulationStart + (minute - (data.startOffsetMinutes ?? 0)) * 60_000
  ).toISOString()
}

function buildFrozenSummary(data: SimulationData): FrozenSimulationSummary {
  const finishedAt = finalVisualFinishedAtMs(data)
  const startedAt = data.realStartedAt ? Date.parse(data.realStartedAt) : Number.NaN
  const realTimeMs = Math.max(0, finishedAt - (Number.isFinite(startedAt) ? startedAt : finishedAt))
  const maxTick = data.maxTick ?? data.tick
  const stoppedAt = simulationMinuteISOString(data, maxTick)

  return {
    simulationId: simulationIdentity(data),
    realTimeMs,
    data: {
      ...data,
      tick: maxTick,
      visualEndTick: maxTick,
      realFinishedAt: new Date(finishedAt).toISOString(),
      simulationStoppedDateTime: stoppedAt ?? data.simulationEndDateTime,
    },
  }
}

function readFinalSummaryStorage(): FrozenSimulationSummary | null {
  try {
    const raw = window.localStorage.getItem(FINAL_SUMMARY_KEY)
    return raw ? JSON.parse(raw) as FrozenSimulationSummary : null
  } catch {
    return null
  }
}

function writeFinalSummaryStorage(summary: FrozenSimulationSummary) {
  try {
    window.localStorage.setItem(FINAL_SUMMARY_KEY, JSON.stringify(summary))
  } catch {
    // The in-memory snapshot still keeps the modal fixed for this page load.
  }
}

function clearFinalSummaryStorage() {
  try {
    window.localStorage.removeItem(FINAL_SUMMARY_KEY)
  } catch {
    // Local storage can be unavailable in some browser privacy modes.
  }
}

function elapsedRealTimeMs(data: SimulationData | null) {
  if (!data?.realStartedAt) return 0

  const startedAt = Date.parse(data.realStartedAt)
  if (!Number.isFinite(startedAt)) return 0

  const finishedAt =
    data.status === "COMPLETED"
      ? finalVisualFinishedAtMs(data)
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
  const assignedAirportTime = useAssignedAirportTime()
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
  const [selectedShipment, setSelectedShipment] = useState<Shipment | null>(null)
  const [mapFocusTarget, setMapFocusTarget] = useState<MapFocusTarget | null>(null)
  const [mapResetViewToken, setMapResetViewToken] = useState(0)
  const [initialFinalSummary] = useState(readFinalSummaryStorage)
  const [reportDismissed, setReportDismissed] = useState(false)
  const [stopSummaryOpen, setStopSummaryOpen] = useState(false)
  const [stoppedSummaryData, setStoppedSummaryData] = useState<SimulationData | null>(null)
  const [stoppedSummaryRealTimeMs, setStoppedSummaryRealTimeMs] = useState(0)
  const [stoppedSummaryMinute, setStoppedSummaryMinute] = useState<number | null>(null)
  const [finalSummaryData, setFinalSummaryData] = useState<SimulationData | null>(
    () => initialFinalSummary?.data ?? null
  )
  const [finalSummaryRealTimeMs, setFinalSummaryRealTimeMs] = useState(
    () => initialFinalSummary?.realTimeMs ?? 0
  )
  const [realTimeMs, setRealTimeMs]   = useState(0)
  const [leftPanelOpen, setLeftPanelOpen] = useState(false)
  const [rightPanelOpen, setRightPanelOpen] = useState(false)
  const [openRightPanelSection, setOpenRightPanelSection] = useState<RightPanelSection | null>(null)
  const [flightColorFilter, setFlightColorFilter] = useState<ColorFilter>("Todos")

  const [airportColorFilter, setAirportColorFilter] = useState<ColorFilter>("Todos")

  // Guarda el tick que tenía la animación ANTES de pedir el lote siguiente,
  // para pasar el "from" correcto a animateBatch cuando llega la respuesta.
  const prevTickRef     = useRef(0)
  const focusTokenRef   = useRef(0)
  // true mientras la animación del lote está corriendo
  const animatingRef    = useRef(false)
  const stoppedSimulationIdRef = useRef<string | null>(null)

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









  const isBatchStoppedLocally = useCallback((simulationId?: string | null) => {
    return Boolean(simulationId && stoppedSimulationIdRef.current === simulationId)
  }, [])

  const currentFinalSummaryMatches = sameSimulation(finalSummaryData, data)
  const currentStoppedSummaryMatches = sameSimulation(stoppedSummaryData, data)

  const captureFinalSummary = useCallback((payload: SimulationData) => {
    const summary = buildFrozenSummary(payload)
    setFinalSummaryData(summary.data)
    setFinalSummaryRealTimeMs(summary.realTimeMs)
    setRealTimeMs(summary.realTimeMs)
    writeFinalSummaryStorage(summary)
    return summary
  }, [])

  const resetFinalSummary = useCallback(() => {
    setFinalSummaryData(null)
    setFinalSummaryRealTimeMs(0)
    clearFinalSummaryStorage()
  }, [])

  const showReport = Boolean(
    data?.status === "COMPLETED" &&
    currentFinalSummaryMatches &&
    !reportDismissed
  )
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
      setSelectedShipment(null)
      setMapFocusTarget({ type: "airport", id: stored.id, token: ++focusTokenRef.current })
      return
    }

    if (stored?.type === "flight" && payload.flights.some((flight) => flight.id === stored.id)) {
      setSelectedAirport(null)
      setSelectedFlightId(stored.id)
      setSelectedShipment(null)
      setMapFocusTarget({ type: "flight", id: stored.id, token: ++focusTokenRef.current })
      return
    }

    writeMapFocus(SIMULATION_MAP_FOCUS_KEY, null)
    setSelectedAirport(null)
    setSelectedFlightId(null)
    setSelectedShipment(null)
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
    if (currentFinalSummaryMatches) {
      setRealTimeMs(finalSummaryRealTimeMs)
      return
    }

    if (currentStoppedSummaryMatches) {
      setRealTimeMs(stoppedSummaryRealTimeMs)
      return
    }

    setRealTimeMs(elapsedRealTimeMs(data))

    if (!playing || !data?.realStartedAt || data.status === "COMPLETED") return

    const t = window.setInterval(() => {
      setRealTimeMs(elapsedRealTimeMs(data))
    }, 1000)

    return () => window.clearInterval(t)
  }, [currentFinalSummaryMatches, currentStoppedSummaryMatches, data, finalSummaryRealTimeMs, playing, stoppedSummaryRealTimeMs])

  // ── Pedir siguiente lote ───────────────────────────────────────────────────
  const fetchNextBatch = useCallback(async (sessionId: string, fromTick: number) => {
    if (fetching) return
    if (isBatchStoppedLocally(sessionId)) return
    if (data?.simulationId === sessionId && !ownsBatchSimulation(data)) return
    setFetching(true)
    setError("")
    try {
      const stepMinutes = data?.planningWindowMinutes ?? data?.batchMinutes ?? BATCH_MINUTES
      const payload = await advanceBatchSimulationRequest(sessionId, stepMinutes, fromTick)
      if (isBatchStoppedLocally(sessionId) || wasBatchSimulationStopped(payload)) {
        stopAnimation()
        setPlaying(false)
        setBatchSimulationPaused(true)
        animatingRef.current = false
        return
      }

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
      if (!isBatchStoppedLocally(sessionId)) {
        setError(err instanceof Error ? err.message : "Error al actualizar la simulación.")
        setPlaying(false)
        animatingRef.current = false
      }
    } finally {
      setFetching(false)
    }
  }, [fetching, data, BATCH_MINUTES, animateBatch, isBatchStoppedLocally, maxMinute, setPlaying, stopAnimation])

  // ── Callback que dispara el hook cuando termina la animación de un lote ────
  useEffect(() => {
    onBatchCompleteRef.current = () => {
      animatingRef.current = false

      if (
        !data?.simulationId ||
        data.status === "COMPLETED" ||
        !playing ||
        !ownsBatchSimulation(data) ||
        isBatchStoppedLocally(data.simulationId) ||
        wasBatchSimulationStopped(data)
      ) return

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
  }, [data, playing, maxMinute, setPlaying, fetchNextBatch, isBatchStoppedLocally, onBatchCompleteRef])

  useEffect(() => {
    if (!data || data.status !== "COMPLETED" || simMinute < maxMinute) return

    setPlaying(false)
    animatingRef.current = false

    if (!sameSimulation(finalSummaryData, data)) {
      captureFinalSummary(data)
    }
  }, [captureFinalSummary, data, finalSummaryData, maxMinute, setPlaying, simMinute])

  useEffect(() => {
    let cancelled = false
    void getCurrentBatchSimulationRequest()
      .then((payload) => {
        if (cancelled || !payload) return
        if (wasBatchSimulationStopped(payload)) {
          setBatchSimulationPaused(true)
          return
        }
        syncControlFieldsFromSimulation(payload)
        setData(payload)
        setReportDismissed(payload.status === "COMPLETED")
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
    if (
      !data?.simulationId ||
      data.status === "COMPLETED" ||
      sameSimulation(stoppedSummaryData, data) ||
      wasBatchSimulationStopped(data)
    ) return

    const timer = window.setInterval(() => {
      if (fetching) return
      void getCurrentBatchSimulationRequest()
        .then((payload) => {
          if (!payload) {
            if (sameSimulation(stoppedSummaryData, data) || wasBatchSimulationStopped(data)) {
              stopAnimation()
              setPlaying(false)
              setBatchSimulationPaused(true)
              animatingRef.current = false
              return
            }

            stopAnimation()
            setPlaying(false)
            setData(null)
            setSelectedAirport(null)
            setSelectedFlightId(null)
            setSelectedShipment(null)
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
          const snapshotChanged =
            simulationSyncSignature(payload) !== simulationSyncSignature(data)

          if (!snapshotChanged) {
            setPlaying(payload.status === "RUNNING")
            return
          }

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
  }, [data, fetching, stoppedSummaryData, animateBatch, maxMinute, restoreMapFocus, setPlaying, stopAnimation, syncControlFieldsFromSimulation, syncSharedVisualWindow])

  // ── Iniciar simulación ─────────────────────────────────────────────────────
  const runSimulation = async () => {
    setLoading(true)
    setError("")
    setNotice("")
    setReportDismissed(false)
    setStopSummaryOpen(false)
    setStoppedSummaryData(null)
    setStoppedSummaryRealTimeMs(0)
    setStoppedSummaryMinute(null)
    clearBatchSimulationStopped()
    writeMapFocus(SIMULATION_MAP_FOCUS_KEY, null)
    resetFinalSummary()
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
      setSelectedShipment(null)
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
    if (!id) { setError("Ingresa el código de un vuelo."); return }
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
      setSelectedShipment(null)
      setMapFocusTarget(null)
      writeMapFocus(SIMULATION_MAP_FOCUS_KEY, null)
      setNotice(`Vuelo ${id} cancelado. Aplicando replanificación al siguiente batch.`)
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
    () =>
      data
        ? { ...data, flights: mergeFlightCatalog(data, flightCatalog) }
        : catalogSimulationData(airportCatalog, flightCatalog),
    [airportCatalog, data, flightCatalog]
  )
  const airportLoads = useMemo(
    () => computeAirportLoads(displayData, simMinute),
    [displayData, simMinute]
  )
  const airportPeakLoads = useMemo(
    () => computeAirportPeakLoads(displayData, simMinute),
    [displayData, simMinute]
  )
  const activeFlights = useMemo(
    () => computeActiveFlights(displayData, simMinute),
    [displayData, simMinute]
  )
  const filteredActiveFlights = useMemo(
    () =>
      flightColorFilter === "Todos"
        ? activeFlights
        : activeFlights.filter((flight) => capacityStatus(flight.utilization) === flightColorFilter),
    [activeFlights, flightColorFilter]
  )
  const activeFlightIds = useMemo(
    () => new Set(activeFlights.map(f => f.id)),
    [activeFlights]
  )
  const mapSelectedFlightId = useMemo(() => {
    if (!selectedFlightId) return null
    if (flightColorFilter === "Todos") return selectedFlightId
    const selectedFlight = displayData.flights.find((flight) => flight.id === selectedFlightId)
    return selectedFlight && capacityStatus(selectedFlight.utilization) === flightColorFilter ? selectedFlightId : null
  }, [displayData.flights, flightColorFilter, selectedFlightId])
  const mapSelectedAirport = useMemo(() => {
    if (!selectedAirport) return null
    if (airportColorFilter === "Todos") return selectedAirport
    const airport = displayData.airports.find((item) => item.code === selectedAirport)
    if (!airport) return null
    const load = airportLoads[airport.code] || 0
    return capacityStatus(load / airport.maxCapacity) === airportColorFilter ? selectedAirport : null
  }, [airportColorFilter, airportLoads, displayData.airports, selectedAirport])
  const visibleShipments = useMemo(
    () => [...(displayData.shipments ?? [])].sort((a, b) => a.requestMinute - b.requestMinute),
    [displayData.shipments]
  );
  const controlsBusy = loading || fetching || cancelling
  const ownsCurrentSimulation = ownsBatchSimulation(data)
  const canControlSimulation = !data?.simulationId || ownsCurrentSimulation
  const simulationCompleted = data?.status === "COMPLETED"
  const finalSummaryAvailable = Boolean(
    simulationCompleted &&
    finalSummaryData &&
    currentFinalSummaryMatches
  )
  const stoppedSummaryAvailable = Boolean(
    stoppedSummaryData &&
    data &&
    currentStoppedSummaryMatches
  )
  const storedSummaryAvailable = finalSummaryAvailable || stoppedSummaryAvailable
  const storedSummaryOpen = showReport || stopSummaryOpen
  const canShowSimulationActions = canControlSimulation || simulationCompleted || storedSummaryAvailable
  const showSimulationFinalActions = simulationCompleted || storedSummaryAvailable
  const displayGmtOffset = assignedAirportTime?.gmtOffset
  const displayAirportLabel = assignedAirportTime
    ? `Hora local ${assignedAirportTime.code} - ${assignedAirportTime.city || "aeropuerto"}`
    : undefined

  const clearMapSelection = useCallback((options?: { resetView?: boolean }) => {
    setSelectedAirport(null)
    setSelectedFlightId(null)
    setSelectedShipment(null)
    setMapFocusTarget(null)
    if (options?.resetView) setMapResetViewToken((token) => token + 1)
    writeMapFocus(SIMULATION_MAP_FOCUS_KEY, null)
  }, [])

  const focusAirport = useCallback((code: string) => {
    // Si es el mismo aeropuerto, deseleccionar
    if (selectedAirport === code) {
      clearMapSelection({ resetView: true })
    } else {
      setSelectedAirport(code)
      setSelectedFlightId(null)
      setSelectedShipment(null)
      setMapFocusTarget({ type: "airport", id: code, token: ++focusTokenRef.current })
      writeMapFocus(SIMULATION_MAP_FOCUS_KEY, { type: "airport", id: code })
    }
  }, [clearMapSelection, selectedAirport])

  const focusFlight = useCallback((id: string) => {
    // Si es el mismo vuelo, deseleccionar
    if (selectedFlightId === id) {
      clearMapSelection({ resetView: true })
    } else {
      setSelectedAirport(null)
      setSelectedFlightId(id)
      setSelectedShipment(null)
      setMapFocusTarget({ type: "flight", id, token: ++focusTokenRef.current })
      writeMapFocus(SIMULATION_MAP_FOCUS_KEY, { type: "flight", id })
    }
  }, [clearMapSelection, selectedFlightId])

  const focusShipment = useCallback((shipment: Shipment) => {
    if (selectedShipment?.id === shipment.id) {
      clearMapSelection({ resetView: true })
      return
    }

    setSelectedAirport(null)
    setSelectedFlightId(null)
    setSelectedShipment(shipment)
    setMapFocusTarget({ type: "shipment", id: shipment.id, token: ++focusTokenRef.current })
    writeMapFocus(SIMULATION_MAP_FOCUS_KEY, null)
  }, [clearMapSelection, selectedShipment?.id])

  const clearSimulationView = (options?: { preserveReportDismissed?: boolean }) => {
    setPlaying(false)
    setSimMinute(0)
    setData(null)
    setError("")
    setNotice("")
    setFlightToCancel("")
    setSelectedAirport(null)
    setSelectedFlightId(null)
    setSelectedShipment(null)
    setMapFocusTarget(null)
    writeMapFocus(SIMULATION_MAP_FOCUS_KEY, null)
    if (!options?.preserveReportDismissed) setReportDismissed(false)
    resetFinalSummary()
    setStopSummaryOpen(false)
    setStoppedSummaryData(null)
    setStoppedSummaryRealTimeMs(0)
    setStoppedSummaryMinute(null)
    clearBatchSimulationStopped()
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
      setError(err instanceof Error ? err.message : "No se pudo pausar la simulación.")
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

    const stoppedSessionId = data.simulationId ?? simulationIdentity(data)
    const elapsed = realTimeMs || elapsedRealTimeMs(data)
    const stoppedMinute =
      simMinute ??
      data.visualEndTick ??
      data.tick ??
      data.startOffsetMinutes ??
      0
    const stoppedAt = simulationMinuteISOString(data, stoppedMinute)
    const stoppedData: SimulationData = {
      ...data,
      visualEndTick: stoppedMinute,
      realFinishedAt: new Date().toISOString(),
      runtimeMs: elapsed,
      simulationStoppedDateTime: stoppedAt ?? data.simulationStoppedDateTime,
    }
    stoppedSimulationIdRef.current = stoppedSessionId || null
    markBatchSimulationStopped(data)
    setBatchSimulationPaused(true)
    setRealTimeMs(elapsed)
    setPlaying(false)
    setFetching(false)
    stopAnimation()
    onBatchCompleteRef.current = null
    animatingRef.current = false
    setStoppedSummaryData(stoppedData)
    setStoppedSummaryRealTimeMs(elapsed)
    setStoppedSummaryMinute(stoppedMinute)
    setStopSummaryOpen(true)
    if (data.simulationId) {
      void stopBatchSimulationRequest(data.simulationId).catch(() => {
        // The local stopped marker still prevents this browser from restoring it.
      })
    }
  }

  const handleStopSummaryOpenChange = (open: boolean) => {
    setStopSummaryOpen(open)
  }

  const handleShowFinalReport = () => {
    if (!finalSummaryData) return
    setData(finalSummaryData)
    setSimMinute(finalSummaryData.maxTick ?? finalSummaryData.tick ?? maxMinute)
    setRealTimeMs(finalSummaryRealTimeMs)
    setPlaying(false)
    animatingRef.current = false
    setReportDismissed(false)
  }

  const handleShowStoppedReport = () => {
    if (!stoppedSummaryData) return

    const stoppedMinute =
      stoppedSummaryMinute ??
      stoppedSummaryData.visualEndTick ??
      stoppedSummaryData.tick ??
      stoppedSummaryData.startOffsetMinutes ??
      simMinute

    setData(stoppedSummaryData)
    setSimMinute(stoppedMinute)
    setRealTimeMs(stoppedSummaryRealTimeMs)
    setPlaying(false)
    animatingRef.current = false
    setStopSummaryOpen(true)
  }

  const handleShowStoredReport = () => {
    if (finalSummaryAvailable) {
      handleShowFinalReport()
      return
    }

    handleShowStoppedReport()
  }

  const handleClearSimulation = async () => {
    const simulationId = data?.simulationId
    setFetching(true)
    setError("")
    try {
      if (simulationId) {
        await stopBatchSimulationRequest(simulationId)
      }
      clearSimulationView()
    } catch (err) {
      const status = (err as { response?: { status?: number } })?.response?.status
      if (status === 404) {
        clearSimulationView()
        return
      }
      setError(err instanceof Error ? err.message : "No se pudo limpiar la simulación.")
    } finally {
      setFetching(false)
    }
  }

  const handleFinalReportOpenChange = (open: boolean) => {
    if (open) return

    setReportDismissed(true)
  }

  const toggleRightPanelSection = (section: RightPanelSection) => {
    setOpenRightPanelSection((current) => current === section ? null : section)
  }

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
              if (data?.status === "COMPLETED") return
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
                    setError(err instanceof Error ? err.message : "No se pudo reanudar la simulación.")
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
          {canShowSimulationActions && (
            <section className="panel section simulation-bottom-actions">
              {showSimulationFinalActions ? (
                <div className="simulation-final-actions">
                  {storedSummaryAvailable && (
                    <button
                      type="button"
                      className="primary"
                      onClick={handleShowStoredReport}
                      disabled={storedSummaryOpen}
                    >
                      Mostrar resumen
                    </button>
                  )}
                  <button
                    type="button"
                    className="danger"
                    onClick={handleClearSimulation}
                    disabled={fetching}
                  >
                    Limpiar simulación
                  </button>
                </div>
              ) : canControlSimulation ? (
                <div className="segmented">
                  <button onClick={handlePause} disabled={!data?.simulationId || controlsBusy || !playing}>Pausar</button>
                  <button onClick={handleRestart} disabled={!data?.simulationId || controlsBusy}>Reiniciar</button>
                  <button className="danger" onClick={handleStop} disabled={!data?.simulationId || controlsBusy}>Cancelar</button>
                </div>
              ) : null}
            </section>
          )}
          <section className="panel section">
            <h3>Indicadores</h3>
            {data ? (
              <Metrics data={data} />
            ) : (
              <div className="empty-state">Ejecuta el simulador para ver métricas.</div>
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

        <section className="panel map-panel">
          <MapStage
            data={displayData}
            activeFlights={filteredActiveFlights}
            airportLoads={airportLoads}
            airportPeakLoads={airportPeakLoads}
            airportColorFilter={airportColorFilter}
            selectedAirport={mapSelectedAirport}
            selectedFlightId={mapSelectedFlightId}
            selectedShipment={selectedShipment}
            focusTarget={mapFocusTarget}
            resetViewToken={mapResetViewToken}
            displayGmtOffset={displayGmtOffset}
            onSelectAirport={focusAirport}
            onSelectFlight={focusFlight}
            onClearSelection={clearMapSelection}
          >
            <SimulationStatusCards
              data={data}
              simMinute={simMinute}
              durationMs={realTimeMs}
              displayGmtOffset={displayGmtOffset}
              displayAirportLabel={displayAirportLabel}
            />
            <DraggableMapOverlay anchor="bottom-right" initialX={18} initialY={76} className="map-global-indicators-overlay">
              <GlobalIndicators
                data={data}
                currentMinute={simMinute}
                samplingIntervalMinutes={data?.planningIntervalMinutes}
              />
            </DraggableMapOverlay>
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
                {loadingFlights && <div className="empty-state">Cargando vuelos...</div>}
                <FlightsTable
                  flights={displayData.flights}
                  activeFlightIds={activeFlightIds}
                  shipments={visibleShipments}
                  data={displayData}
                  selectedFlightId={selectedFlightId}
                  onSelectFlight={focusFlight}
                  displayGmtOffset={displayGmtOffset}
                  colorFilter={flightColorFilter}
                  onColorFilterChange={setFlightColorFilter}
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
                {loadingFlights && <div className="empty-state">Cargando envíos...</div>}
                <ShipmentsTable
                  shipments={visibleShipments}
                  flights={displayData.flights}
                  data={displayData}
                  simMinute={simMinute}
                  displayGmtOffset={displayGmtOffset}
                  selectedShipmentId={selectedShipment?.id ?? null}
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
            {loadingAirports && <div className="empty-state">Cargando aeropuertos...</div>}
            {displayData.airports.length ? (
              <AirportsTable
                airports={displayData.airports}
                loads={airportLoads}
                flights={displayData.flights}
                shipments={visibleShipments}
                simMinute={simMinute}
                selectedAirport={selectedAirport}
                displayGmtOffset={displayGmtOffset}
                onSelectAirport={focusAirport}
                colorFilter={airportColorFilter}
                onColorFilterChange={setAirportColorFilter}
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
        open={showReport}
        onOpenChange={handleFinalReportOpenChange}
        data={finalSummaryData}
        realTimeMs={finalSummaryRealTimeMs}
        footerLabel="Cerrar"
        closeOnOutside={false}
        showHeaderClose={false}
      />
      <SimulationResultModal
        open={stopSummaryOpen}
        onOpenChange={handleStopSummaryOpenChange}
        data={stoppedSummaryData}
        realTimeMs={stoppedSummaryRealTimeMs}
        title="Resumen de simulación actual"
        description=""
        footerLabel="Cerrar"
        closeOnOutside={false}
        showHeaderClose={false}
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
            placeholder="ID: SKBO-VIDP-0005"
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
