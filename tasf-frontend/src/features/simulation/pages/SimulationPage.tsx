import { useEffect, useMemo, useState, useRef, useCallback } from "react"
import { runSimulationRequest } from "../../../api/simulationApi"
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
import { DAY_OPTIONS, DEFAULT_START_DATE, SPEED_MAX, SPEED_MIN, SPEED_STEP } from "../utils/constants"
import { computeActiveFlights, computeAirportLoads } from "../utils/calculations"

import { SimulationResultModal } from "../components/SimulationResultModal"

export function SimulationPage() {
  const [startDate, setStartDate] = useState(DEFAULT_START_DATE)
  const [days, setDays] = useState(3)
  const [data, setData] = useState<SimulationData | null>(null)
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState("")
  const [selectedAirport, setSelectedAirport] = useState<string | null>(null)
  const [reportDismissed, setReportDismissed] = useState(false)
  const [now, setNow] = useState(new Date())
  const [realTimeMs, setRealTimeMs] = useState(0)
  const accumulatedRef = useRef(0)
  const playStartRef = useRef<number | null>(null)

  const maxMinute = days * 1440
  const { simMinute, setSimMinute, playing, setPlaying, speed, setSpeed } =
    useSimulationPlayer(maxMinute)

  const resetRealTime = useCallback(() => {
    accumulatedRef.current = 0
    playStartRef.current = null
    setRealTimeMs(0)
  }, [])

  useEffect(() => {
    const timer = window.setInterval(() => setNow(new Date()), 1000)
    return () => window.clearInterval(timer)
  }, [])

  useEffect(() => {
    if (playing) {
      if (!playStartRef.current) playStartRef.current = Date.now()
      
      const timer = window.setInterval(() => {
        setRealTimeMs(accumulatedRef.current + (Date.now() - (playStartRef.current ?? 0)))
      }, 100)

      return () => window.clearInterval(timer)
    } else {
      if (playStartRef.current) {
        accumulatedRef.current += Date.now() - playStartRef.current
        playStartRef.current = null
        setRealTimeMs(accumulatedRef.current)
      }
    }
  }, [playing])

  const formatRealTime = (ms: number) => {
    const totalSec = Math.floor(ms / 1000)
    const h = Math.floor(totalSec / 3600)
    const m = Math.floor((totalSec % 3600) / 60)
    const s = totalSec % 60
    return h > 0
      ? `${h}:${m.toString().padStart(2, "0")}:${s.toString().padStart(2, "0")}`
      : `${m.toString().padStart(2, "0")}:${s.toString().padStart(2, "0")}`
  }

  const showReport = Boolean(data && simMinute >= maxMinute && !reportDismissed)

  const runSimulation = async () => {
    setLoading(true)
    setError("")
    setPlaying(false)
    setSimMinute(0)
    setReportDismissed(false)
    resetRealTime()

    try {
      const payload = await runSimulationRequest(startDate, days)
      setData(payload)
      setSelectedAirport(payload.airports[0]?.code || null)
    } catch (err) {
      setError(err instanceof Error ? err.message : "No se pudo simular.")
    } finally {
      setLoading(false)
    }
  }

  const airportLoads = useMemo(
    () => computeAirportLoads(data, simMinute),
    [data, simMinute]
  )
  const activeFlights = useMemo(
    () => computeActiveFlights(data, simMinute),
    [data, simMinute]
  )
  const selected = data?.airports.find((airport) => airport.code === selectedAirport)

  const handleDaysChange = (option: number) => {
    setDays(option)
    setSimMinute(0)
    setReportDismissed(false)
  }

  const handleReset = () => {
    setPlaying(false)
    setSimMinute(0)
    setReportDismissed(false)
    resetRealTime()
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
            loading={loading}
            playing={playing}
            speed={speed}
            startDate={startDate}
            onDaysChange={handleDaysChange}
            onPause={() => setPlaying(false)}
            onPlay={() => setPlaying(true)}
            onReset={handleReset}
            onRunSimulation={runSimulation}
            onSpeedChange={setSpeed}
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
              <AirportDetail
                airport={selected}
                load={airportLoads[selected.code] || 0}
              />
            )}
          </section>
          {/* <Button onClick={() => setShowReport(true)}>
            Abrir modal
          </Button> */}

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
      data={data}/>
    </div>
  )
}

type SimulationControlsProps = {
  days: number
  error: string
  loading: boolean
  playing: boolean
  speed: number
  startDate: string
  onDaysChange: (days: number) => void
  onPause: () => void
  onPlay: () => void
  onReset: () => void
  onRunSimulation: () => void
  onSpeedChange: (speed: number) => void
  onStartDateChange: (date: string) => void
}

function SimulationControls({
  days,
  error,
  loading,
  playing,
  speed,
  startDate,
  onDaysChange,
  onPause,
  onPlay,
  onReset,
  onRunSimulation,
  onSpeedChange,
  onStartDateChange,
}: SimulationControlsProps) {
  return (
    <section className="panel section">
      <h2>Simulador</h2>
      <div className="control-grid">
        <div className="field">
          <label>Fecha inicial</label>
          <input
            type="date"
            value={startDate}
            onChange={(event) => onStartDateChange(event.target.value)}
          />
        </div>

        <div className="field">
          <label>Dias de simulación</label>
          <div className="segmented">
            {DAY_OPTIONS.map((option) => (
              <button
                key={option}
                className={option === days ? "active" : ""}
                onClick={() => onDaysChange(option)}
              >
                {`${option} días`}
              </button>
            ))}
          </div>
        </div>

        <button className="primary" onClick={onRunSimulation} disabled={loading}>
          {loading ? "Ejecutando ALNS..." : "Ejecutar simulación"}
        </button>

        {error && <div className="error">{error}</div>}

        <div className="speed-row">
          <input
            type="range"
            min={SPEED_MIN}
            max={SPEED_MAX}
            step={SPEED_STEP}
            value={speed}
            onChange={(event) => onSpeedChange(Number(event.target.value))}
          />
          <strong>{`${speed}x`}</strong>
        </div>

        <div className="segmented">
          <button onClick={onPlay} className={playing ? "active" : ""}>
            Play
          </button>
          <button onClick={onPause}>Pausa</button>
          <button onClick={onReset}>Reset</button>
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
        <div className="legend-row">
          <span className="dot green"></span>
          Menor a 70%
        </div>
        <div className="legend-row">
          <span className="dot yellow"></span>
          Desde 70% hasta menor a 90%
        </div>
        <div className="legend-row">
          <span className="dot red"></span>
          90% o más
        </div>
      </div>
    </section>
  )
}
