import { useEffect, useMemo, useState } from "react";
import { runSimulationRequest } from "../../../api/simulationApi";
import { Navbar } from "../../global/Navbar";
import { AirportDetail } from "../components/AirportDetail";
import { AirportsTable } from "../components/AirportsTable";
import { FlightsTable } from "../components/FlightsTable";
import { Metrics } from "../components/Metrics";
import MapStage from "../components/simulation/map/MapStage";
import { Timeline } from "../components/Timeline";
import { Topbar } from "../components/Topbar";
import { useSimulationPlayer } from "../hooks/useSimulationPlayer";
import type { SimulationData } from "../types";
import { DAY_OPTIONS, DEFAULT_START_DATE, SPEED_MAX, SPEED_MIN, SPEED_STEP } from "../utils/constants";
import { computeActiveFlights, computeAirportLoads } from "../utils/calculations";

export function SimulationPage() {
  const [startDate, setStartDate] = useState(DEFAULT_START_DATE);
  const [days, setDays] = useState(3);
  const [data, setData] = useState<SimulationData | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState("");
  const [selectedAirport, setSelectedAirport] = useState<string | null>(null);
  const [now, setNow] = useState(new Date());

  const maxMinute = days * 1440;
  const { simMinute, setSimMinute, playing, setPlaying, speed, setSpeed } =
    useSimulationPlayer(maxMinute);

  useEffect(() => {
    const timer = window.setInterval(() => setNow(new Date()), 1000);
    return () => window.clearInterval(timer);
  }, []);

  const runSimulation = async () => {
    setLoading(true);
    setError("");
    setPlaying(false);
    setSimMinute(0);

    try {
      const payload = await runSimulationRequest(startDate, days);
      setData(payload);
      setSelectedAirport(payload.airports[0]?.code || null);
    } catch (err) {
      setError(err instanceof Error ? err.message : "No se pudo simular.");
    } finally {
      setLoading(false);
    }
  };

  const airportLoads = useMemo(
    () => computeAirportLoads(data, simMinute),
    [data, simMinute]
  );
  const activeFlights = useMemo(
    () => computeActiveFlights(data, simMinute),
    [data, simMinute]
  );
  const selected = data?.airports.find((airport) => airport.code === selectedAirport);

  const handleDaysChange = (option: number) => {
    setDays(option);
    setSimMinute(0);
  };

  const handleReset = () => {
    setPlaying(false);
    setSimMinute(0);
  };

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
              <div className="empty-state">Ejecuta ALNS para ver metricas.</div>
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
          <section className="panel section">
            <h3>{selected ? `${selected.code} - ${selected.city}` : "Aeropuerto"}</h3>
            {selected && (
              <AirportDetail
                airport={selected}
                load={airportLoads[selected.code] || 0}
              />
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
}

type SimulationControlsProps = {
  days: number;
  error: string;
  loading: boolean;
  playing: boolean;
  speed: number;
  startDate: string;
  onDaysChange: (days: number) => void;
  onPause: () => void;
  onPlay: () => void;
  onReset: () => void;
  onRunSimulation: () => void;
  onSpeedChange: (speed: number) => void;
  onStartDateChange: (date: string) => void;
};

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
      <h2>Simulador ALNS</h2>
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
          <label>Dias de simulacion</label>
          <div className="segmented">
            {DAY_OPTIONS.map((option) => (
              <button
                key={option}
                className={option === days ? "active" : ""}
                onClick={() => onDaysChange(option)}
              >
                {`${option} dias`}
              </button>
            ))}
          </div>
        </div>

        <button className="primary" onClick={onRunSimulation} disabled={loading}>
          {loading ? "Ejecutando ALNS..." : "Ejecutar simulacion"}
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
  );
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
          90% o mas
        </div>
      </div>
    </section>
  );
}
