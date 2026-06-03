import { useEffect, useMemo, useState } from "react";
import {
  Bar,
  BarChart,
  CartesianGrid,
  Line,
  LineChart,
  ResponsiveContainer,
  Tooltip,
  XAxis,
  YAxis,
} from "recharts";
import {
  refreshRealtimeOperationRequest,
  runRealtimeOperationRequest,
} from "../../../api/simulationApi";
import { Navbar } from "../../../shared/components/Navbar/Navbar";
import type { SimulationData } from "../../simulation/types";
import { percent } from "../../simulation/utils/formatters";

function hourLabel(hour: number) {
  return `${String(hour).padStart(2, "0")}:00`;
}

function buildThroughput(data: SimulationData) {
  const buckets = Array.from({ length: 12 }, (_, index) => {
    const hour = index * 2;
    return { hour: hourLabel(hour), planned: 0, actual: 0 };
  });

  for (const shipment of data.shipments) {
    const minuteOfDay = ((shipment.requestMinute % 1440) + 1440) % 1440;
    const bucketIndex = Math.min(11, Math.floor(minuteOfDay / 120));
    buckets[bucketIndex].planned += shipment.suitcases;
    if (shipment.planned) {
      buckets[bucketIndex].actual += shipment.suitcases;
    }
  }

  return buckets;
}

function buildAirportUse(data: SimulationData) {
  return data.airports
    .map((airport) => ({
      airport: airport.code,
      utilization: Math.round(airport.utilization * 100),
    }))
    .sort((a, b) => b.utilization - a.utilization)
    .slice(0, 10);
}

function buildInsights(data: SimulationData) {
  const mostUsedAirport = [...data.airports].sort(
    (a, b) => b.utilization - a.utilization
  )[0];
  const busiestBucket = buildThroughput(data).sort(
    (a, b) => b.actual - a.actual
  )[0];
  const delayedBags = data.shipments.reduce(
    (sum, shipment) => (!shipment.onTime ? sum + shipment.suitcases : sum),
    0
  );

  return [
    {
      title: mostUsedAirport
        ? `${mostUsedAirport.code} esta cerca del limite.`
        : "Sin aeropuertos cargados.",
      detail: mostUsedAirport
        ? `La utilizacion llega a ${Math.round(
            mostUsedAirport.utilization * 100
          )}% durante la simulacion.`
        : "Ejecuta la simulacion para poblar los indicadores.",
    },
    {
      title: busiestBucket
        ? `El pico de throughput aparece a las ${busiestBucket.hour}.`
        : "Sin throughput calculado.",
      detail: busiestBucket
        ? `El backend reporta ${busiestBucket.actual} maletas planificadas en ese bloque.`
        : "No hay envios disponibles en el resultado recibido.",
    },
    {
      title: `${delayedBags.toLocaleString("es-PE")} maletas requieren seguimiento.`,
      detail:
        "La cifra agrupa envios fuera de SLA o no planificados en la respuesta ALNS.",
    },
  ];
}

export const StatsPage = () => {
  const [data, setData] = useState<SimulationData | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState("");

  const loadData = async () => {
    setLoading(true);
    setError("");

    try {
      const payload = await refreshRealtimeOperationRequest();
      setData(payload);
    } catch (err) {
      setError(err instanceof Error ? err.message : "No se pudo cargar estadisticas.");
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    let ignore = false;

    void runRealtimeOperationRequest()
      .then((payload) => {
        if (!ignore) setData(payload);
      })
      .catch((err) => {
        if (!ignore) {
          setError(err instanceof Error ? err.message : "No se pudo cargar estadisticas.");
        }
      })
      .finally(() => {
        if (!ignore) setLoading(false);
      });

    return () => {
      ignore = true;
    };
  }, []);

  const throughput = useMemo(() => (data ? buildThroughput(data) : []), [data]);
  const airportUse = useMemo(() => (data ? buildAirportUse(data) : []), [data]);
  const insights = useMemo(() => (data ? buildInsights(data) : []), [data]);

  const metrics = data?.metrics;
  const planningPct = metrics ? percent(metrics.plannedShipments, metrics.shipments) : 0;
  const onTimePct = metrics ? percent(metrics.onTimeShipments, metrics.shipments) : 0;
  const averageUse = airportUse.length
    ? Math.round(
        airportUse.reduce((sum, airport) => sum + airport.utilization, 0) /
          airportUse.length
      )
    : 0;

  return (
    <div className="app-shell">
      <Navbar />

      <main className="dashboard-workspace">
        <section className="dashboard-heading">
          <div>
            <h1>Estadisticas</h1>
            <p>Resumen de rendimiento, capacidad y cumplimiento de la operacion.</p>
          </div>
          <button className="primary" onClick={loadData} disabled={loading}>
            {loading ? "Actualizando..." : "Actualizar reporte"}
          </button>
        </section>

        {error && <div className="error">{error}</div>}

        <section className="dashboard-grid">
          <div className="panel section metric-panel">
            <span>Planificacion</span>
            <strong>{`${planningPct}%`}</strong>
            <small>envios asignados correctamente</small>
          </div>
          <div className="panel section metric-panel">
            <span>A tiempo</span>
            <strong>{`${onTimePct}%`}</strong>
            <small>maletas dentro de SLA</small>
          </div>
          <div className="panel section metric-panel">
            <span>Uso promedio</span>
            <strong>{`${averageUse}%`}</strong>
            <small>capacidad aeroportuaria</small>
          </div>
        </section>

        <section className="stats-grid">
          <div className="panel section chart-panel">
            <h2>Maletas por hora</h2>
            {throughput.length ? (
              <ResponsiveContainer width="100%" height={280}>
                <LineChart data={throughput} margin={{ left: -18, right: 12, top: 10 }}>
                  <CartesianGrid stroke="#dfe5ec" strokeDasharray="3 3" />
                  <XAxis dataKey="hour" />
                  <YAxis />
                  <Tooltip />
                  <Line type="monotone" dataKey="planned" stroke="#647084" strokeWidth={2} dot={false} />
                  <Line type="monotone" dataKey="actual" stroke="#2563eb" strokeWidth={3} dot={false} />
                </LineChart>
              </ResponsiveContainer>
            ) : (
              <div className="empty-state">
                {loading ? "Cargando datos del backend..." : "Sin envios para graficar."}
              </div>
            )}
          </div>

          <div className="panel section chart-panel">
            <h2>Utilizacion por aeropuerto</h2>
            {airportUse.length ? (
              <ResponsiveContainer width="100%" height={280}>
                <BarChart data={airportUse} margin={{ left: -18, right: 12, top: 10 }}>
                  <CartesianGrid stroke="#dfe5ec" strokeDasharray="3 3" />
                  <XAxis dataKey="airport" />
                  <YAxis />
                  <Tooltip />
                  <Bar dataKey="utilization" fill="#111827" radius={[6, 6, 0, 0]} />
                </BarChart>
              </ResponsiveContainer>
            ) : (
              <div className="empty-state">Sin aeropuertos para graficar.</div>
            )}
          </div>
        </section>

        <section className="panel section">
          <h2>Hallazgos operativos</h2>
          {insights.length ? (
            <div className="insight-list">
              {insights.map((insight) => (
                <div key={insight.title}>
                  <strong>{insight.title}</strong>
                  <span>{insight.detail}</span>
                </div>
              ))}
            </div>
          ) : (
            <div className="empty-state">Sin hallazgos calculados.</div>
          )}
        </section>
      </main>
    </div>
  );
};
