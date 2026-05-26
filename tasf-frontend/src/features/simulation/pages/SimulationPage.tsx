import React, { useEffect, useMemo, useRef, useState } from "react";
import { useSimulationPlayer } from "../hooks/useSimulationPlayer";
import L from "leaflet";
import { Navbar } from "../../global/Navbar";

const STATUS_COLOR = {
  green: "#21a67a",
  yellow: "#d9a219",
  red: "#d84545",
};

export function SimulationPage() {
  const [startDate, setStartDate] = useState("2026-01-02");
  const [days, setDays] = useState(3);
  const [data, setData] = useState(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState("");
  const [selectedAirport, setSelectedAirport] = useState<string | null>(null);
  const [now, setNow] = useState(new Date());
  const maxMinute = days * 1440;
  const { simMinute, setSimMinute, playing, setPlaying, speed, setSpeed } = 
  useSimulationPlayer(maxMinute);

  useEffect(() => {
    const timer = setInterval(() => setNow(new Date()), 1000);
    return () => clearInterval(timer);
  }, []);

  async function runSimulation() {
    setLoading(true);
    setError("");
    setPlaying(false);
    setSimMinute(0);
    try {
      const response = await fetch("/api/simulations/alns", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ startDate: compactDate(startDate), days }),
      });
      const payload = await response.json();
      if (!response.ok) throw new Error(payload.error || "No se pudo simular.");
      console.log(payload)
      setData(payload);
      setSelectedAirport(payload.airports[0]?.code || null);
    } catch (err) {
      setError((err as Error).message);
    } finally {
      setLoading(false);
    }
  }

  const airportLoads = useMemo(() => computeAirportLoads(data, simMinute), [data, simMinute]);
  const activeFlights = useMemo(() => computeActiveFlights(data, simMinute), [data, simMinute]);
  const selected = data?.airports?.find((airport: any) => airport.code === selectedAirport);

  return (
    React.createElement("div", { className: "app-shell" },
        React.createElement(Navbar, null),
      React.createElement(Topbar, { data, now, simMinute }),
      React.createElement("main", { className: "workspace" },
        React.createElement("aside", { className: "side-panel" },
          React.createElement("section", { className: "panel section" },
            React.createElement("h2", null, "Simulador ALNS"),
            React.createElement("div", { className: "control-grid" },
              React.createElement("div", { className: "field" },
                React.createElement("label", null, "Fecha inicial"),
                React.createElement("input", {
                  type: "date",
                  value: startDate,
                  onChange: (event) => setStartDate(event.target.value),
                })
              ),
              React.createElement("div", { className: "field" },
                React.createElement("label", null, "Dias de simulacion"),
                React.createElement("div", { className: "segmented" },
                  [3, 5, 7].map((option) => React.createElement("button", {
                    key: option,
                    className: option === days ? "active" : "",
                    onClick: () => {
                      setDays(option);
                      setSimMinute(0);
                    },
                  }, `${option} dias`))
                )
              ),
              React.createElement("button", { className: "primary", onClick: runSimulation, disabled: loading },
                loading ? "Ejecutando ALNS..." : "Ejecutar simulacion"
              ),
              error && React.createElement("div", { className: "error" }, error),
              React.createElement("div", { className: "speed-row" },
                React.createElement("input", {
                  type: "range",
                  min: "60",
                  max: "1800",
                  step: "60",
                  value: speed,
                  onChange: (event) => setSpeed(Number(event.target.value)),
                }),
                React.createElement("strong", null, `${speed}x`)
              ),
              React.createElement("div", { className: "segmented" },
                React.createElement("button", { onClick: () => setPlaying(true), className: playing ? "active" : "" }, "Play"),
                React.createElement("button", { onClick: () => setPlaying(false) }, "Pausa"),
                React.createElement("button", { onClick: () => { setPlaying(false); setSimMinute(0); } }, "Reset")
              )
            )
          ),
          React.createElement("section", { className: "panel section" },
            React.createElement("h3", null, "Colores por capacidad"),
            React.createElement("div", { className: "legend" },
              React.createElement("div", { className: "legend-row" }, React.createElement("span", { className: "dot green" }), "Menor a 70%"),
              React.createElement("div", { className: "legend-row" }, React.createElement("span", { className: "dot yellow" }), "Desde 70% hasta menor a 90%"),
              React.createElement("div", { className: "legend-row" }, React.createElement("span", { className: "dot red" }), "90% o mas")
            )
          ),
          React.createElement("section", { className: "panel section" },
            React.createElement("h3", null, "Indicadores"),
            data ? React.createElement(Metrics, { data }) : React.createElement("div", { className: "empty-state" }, "Ejecuta ALNS para ver metricas.")
          )
        ),
        React.createElement("section", { className: "panel map-panel" },
          React.createElement(MapStage, {
            data,
            activeFlights,
            airportLoads,
            selectedAirport,
            onSelectAirport: setSelectedAirport,
          }),
          React.createElement(Timeline, { simMinute, maxMinute, setSimMinute, data })
        ),
        React.createElement("aside", { className: "right-panel" },
          React.createElement("section", { className: "panel section" },
            React.createElement("h3", null, selected ? `${selected.code} · ${selected.city}` : "Aeropuerto"),
            selected && React.createElement(AirportDetail, { airport: selected, load: airportLoads[selected.code] || 0 })
          ),
          React.createElement("section", { className: "panel section" },
            React.createElement("h3", null, "Vuelos activos"),
            React.createElement(FlightsTable, { flights: activeFlights })
          ),
          React.createElement("section", { className: "panel section" },
            React.createElement("h3", null, "Aeropuertos criticos"),
            data ? React.createElement(AirportsTable, { airports: data.airports, loads: airportLoads }) : React.createElement("div", { className: "empty-state" }, "Sin datos.")
          )
        )
      )
    )
  );
}

function Topbar({ data, now, simMinute }: { data: any; now: any; simMinute: any }) {
  return React.createElement("header", { className: "topbar" },
    React.createElement("div", { className: "brand" },
      React.createElement("strong", null, "TASF.B2B · Simulador de equipaje"),
      React.createElement("span", null, "Escenario operativo ALNS")
    ),
    React.createElement("div", { className: "status-strip" },
      React.createElement(StatusItem, { label: "Ahora", value: formatClock(now), sub: formatDateOnly(now) }),
      React.createElement(StatusItem, { label: "Reloj simulado", value: formatSimMinute(simMinute), sub: "avance actual" }),
      React.createElement(StatusItem, { label: "ALNS inicio", value: data ? formatTimeOnly(data.realStartedAt) : "--", sub: data ? formatDateOnly(data.realStartedAt) : "--" }),
      React.createElement(StatusItem, { label: "ALNS fin", value: data ? formatTimeOnly(data.realFinishedAt) : "--", sub: data ? formatDateOnly(data.realFinishedAt) : "--" }),
      React.createElement(StatusItem, { label: "Simulado desde", value: data ? formatDateOnly(data.simulationStartDateTime) : "--", sub: data ? formatTimeOnly(data.simulationStartDateTime) : "--" }),
      React.createElement(StatusItem, { label: "Simulado hasta", value: data ? formatDateOnly(data.simulationEndDateTime) : "--", sub: data ? formatTimeOnly(data.simulationEndDateTime) : "--" }),
      React.createElement(StatusItem, { label: "Duracion ALNS", value: data ? `${(data.runtimeMs / 1000).toFixed(2)} s` : "--", sub: "ejecucion real" }),
      React.createElement(StatusItem, { label: "Escenario", value: data?.scenario || "ALNS", sub: "planificacion" })
    )
  );
}

function StatusItem({ label, value, sub }: { label: any; value: any; sub: any }) {
  return React.createElement("div", { className: "status-item" },
    React.createElement("span", null, label),
    React.createElement("strong", null, value),
    sub && React.createElement("small", null, sub)
  );
}

function Metrics({ data }: { data: any }) {
  const metrics = data.metrics;
  const plannedPct = percent(metrics.plannedShipments, metrics.shipments);
  const onTimePct = percent(metrics.onTimeShipments, metrics.shipments);
  return React.createElement("div", { className: "metrics" },
    React.createElement(Metric, { label: "Envios planificados", value: `${metrics.plannedShipments}/${metrics.shipments}`, sub: `${plannedPct}%` }),
    React.createElement(Metric, { label: "A tiempo", value: `${metrics.onTimeShipments}`, sub: `${onTimePct}%` }),
    React.createElement(Metric, { label: "Maletas", value: metrics.plannedBags, sub: `de ${metrics.totalBags}` }),
    React.createElement(Metric, { label: "Vuelos usados", value: metrics.usedFlights, sub: `${metrics.iterations} iter.` }),
    React.createElement(Metric, { label: "Fitness final", value: Math.round(metrics.fitnessFinal), sub: `ini ${Math.round(metrics.fitnessInitial)}` }),
    React.createElement(Metric, { label: "Aceptadas SA", value: metrics.acceptedBySa, sub: `${metrics.globalImprovements} mejoras` })
  );
}

function Metric({ label, value, sub }: { label: any; value: any; sub: any }) {
  return React.createElement("div", { className: "metric" },
    React.createElement("span", null, label),
    React.createElement("strong", null, value),
    React.createElement("span", null, sub)
  );
}

function MapStage({ data, activeFlights, airportLoads, selectedAirport, onSelectAirport }: { data: any; activeFlights: any; airportLoads: any; selectedAirport: any; onSelectAirport: any }) {
  const [mapInfo, setMapInfo] = useState<any>(null);
  const mapElement = useRef<HTMLDivElement>(null);
  const mapRef = useRef<L.Map | null>(null);
  const routeLayerRef = useRef<L.LayerGroup | null>(null);
  const airportLayerRef = useRef<L.LayerGroup | null>(null);
  const planeLayerRef = useRef<L.LayerGroup | null>(null);
  const airportLoadsRef = useRef<Record<string, number>>({});
  const airportMarkersRef = useRef(new Map());
  const airports = data?.airports || [];
  const airportByCode = useMemo(() => Object.fromEntries(airports.map((airport: any) => [airport.code, airport])), [airports]);

  useEffect(() => {
    airportLoadsRef.current = airportLoads;
  }, [airportLoads]);

  useEffect(() => {
    if (!mapElement.current || mapRef.current) return;

    const map = L.map(mapElement.current, {
        worldCopyJump: true,
        zoomControl: true,
        preferCanvas: true,
        maxBounds: [[-58, -115], [62, 95]],
        maxBoundsViscosity: 0.4,
    }).setView([10, -5], 2);

    mapRef.current = map;

    map.createPane("routes");
    map.createPane("activeFlights");
    map.createPane("airports");

    map.getPane("routes")?.style && (map.getPane("routes")!.style.zIndex = "430");
    map.getPane("activeFlights")?.style && (map.getPane("activeFlights")!.style.zIndex = "620");
    map.getPane("airports")?.style && (map.getPane("airports")!.style.zIndex = "660");

    L.tileLayer("https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png", {
        maxZoom: 8,
        minZoom: 2,
        attribution:
        '&copy; <a href="https://www.openstreetmap.org/copyright">OpenStreetMap</a>',
    }).addTo(map);

    routeLayerRef.current = L.layerGroup().addTo(map);
    airportLayerRef.current = L.layerGroup().addTo(map);
    planeLayerRef.current = L.layerGroup().addTo(map);

    const delays = [0, 100, 350];
    const timers: number[] = [];
    delays.forEach((delay) => {
        const t = window.setTimeout(() => {
        if (mapRef.current) {
            mapRef.current.invalidateSize();
            mapRef.current.setView([10, -5], 2, { animate: false });
        }
        }, delay);
        timers.push(t);
    });

    return () => {
        timers.forEach(clearTimeout);
        if (mapRef.current) {
        mapRef.current.remove(); 
        mapRef.current = null;
        }
    };
    }, []);

  useEffect(() => {
    if (!mapRef.current || !airportLayerRef.current || !L) return;

    airportLayerRef.current.clearLayers();
    airportMarkersRef.current.clear();
    for (const airport of airports) {
      const load = airportLoadsRef.current[airport.code] || 0;
      const status = capacityStatus(load / airport.maxCapacity);
      const isSelected = airport.code === selectedAirport;
      const icon = L.divIcon({
        className: "airport-map-icon",
        html: `<div class="airport-marker ${status}${isSelected ? " selected" : ""}" title="${airport.code} - ${airport.city}"><span></span><strong>${airport.code}</strong></div>`,
        iconSize: [70, 30],
        iconAnchor: [12, 15],
      });
      const marker = L.marker([airport.latitude, airport.longitude], { icon, pane: "airports", interactive: true, keyboard: false, zIndexOffset: 900 })
        .on("click", () => {
          const currentLoad = airportLoadsRef.current[airport.code] || 0;
          onSelectAirport(airport.code);
          setMapInfo({
            type: "airport",
            title: `${airport.code} - ${airport.city}`,
            subtitle: `${airport.country} / ${airport.continent}`,
            rows: [
              ["Codigo", airport.code],
              ["Latitud", `${airport.latitude.toFixed(4)} deg`],
              ["Longitud", `${airport.longitude.toFixed(4)} deg`],
              ["Maletas", `${currentLoad}/${airport.maxCapacity}`],
              ["Uso", `${Math.round((currentLoad / airport.maxCapacity) * 100)}%`],
            ],
          });
        })
        .addTo(airportLayerRef.current);
      airportMarkersRef.current.set(airport.code, { airport, marker });
    }
  }, [airports, onSelectAirport]);

  useEffect(() => {
    for (const airport of airports) {
      const item = airportMarkersRef.current.get(airport.code);
      if (!item) continue;
      const load = airportLoads[airport.code] || 0;
      const status = capacityStatus(load / airport.maxCapacity);
      const element = item.marker.getElement()?.querySelector(".airport-marker");
      if (!element) continue;
      element.className = `airport-marker ${status}${airport.code === selectedAirport ? " selected" : ""}`;
    }
  }, [airports, airportLoads, selectedAirport]);

  useEffect(() => {
    if (!mapRef.current || !planeLayerRef.current || !L) return;

    planeLayerRef.current.clearLayers();
    for (const flight of activeFlights) {
      const origin = airportByCode[flight.origin];
      const dest = airportByCode[flight.destination];
      if (!origin || !dest) continue;

      const lat = origin.latitude + (dest.latitude - origin.latitude) * flight.progress;
      const lon = origin.longitude + (dest.longitude - origin.longitude) * flight.progress;
      const angle = bearingDegrees(origin.latitude, origin.longitude, dest.latitude, dest.longitude);
      const icon = L.divIcon({
        className: "flight-map-icon",
        html: planeSvg(STATUS_COLOR[flight.status as keyof typeof STATUS_COLOR], angle),
        iconSize: [26, 26],
        iconAnchor: [13, 13],
      });
      L.marker([lat, lon], { icon, pane: "activeFlights" })
        .on("click", () => {
          setMapInfo({
            type: "flight",
            title: `Vuelo ${flight.id}`,
            subtitle: `${origin.code} ${origin.city} -> ${dest.code} ${dest.city}`,
            rows: [
              ["Codigo", flight.id],
              ["Origen", `${origin.code} - ${origin.city}`],
              ["Destino", `${dest.code} - ${dest.city}`],
              ["Salida", formatFlightMoment(data, flight.absoluteDepartureMinute)],
              ["Llegada", formatFlightMoment(data, flight.absoluteArrivalMinute)],
              ["Avance", `${Math.round(flight.progress * 100)}%`],
              ["Carga", `${flight.assignedLoad}/${flight.maxCapacity} maletas (${Math.round(flight.utilization * 100)}%)`],
            ],
          });
        })
        .addTo(planeLayerRef.current);
    }
  }, [activeFlights, airportByCode, data]);

  useEffect(() => {
    if (!data || !mapRef.current || !airports.length || !L) return;
    const bounds = L.latLngBounds(airports.map((airport: any) => [airport.latitude, airport.longitude]));
    setTimeout(() => {
      mapRef.current?.invalidateSize();
      mapRef.current?.fitBounds(bounds.pad(0.16), { maxZoom: 3, animate: false });
    }, 0);
  }, [data, airports]);

  return React.createElement("div", { className: "map-stage" },
    React.createElement("div", { className: "map-header" },
      React.createElement("span", { className: "badge" }, data ? `${data.airports.length} aeropuertos` : "Mapa operativo"),
      React.createElement("span", { className: "badge" }, data ? `${activeFlights.length} vuelos en aire` : "ALNS")
    ),
    mapInfo && React.createElement(MapInfoCard, { info: mapInfo, onClose: () => setMapInfo(null) }),
    React.createElement("div", { ref: mapElement, className: "leaflet-map", role: "img", "aria-label": "Mapa mundial con aeropuertos y vuelos activos" }),
    !L && React.createElement("div", { className: "map-load-error" }, "No se pudo cargar el mapa. Revisa tu conexion para Leaflet/OpenStreetMap.")
  );
}

function MapInfoCard({ info, onClose }: { info: any; onClose: any }) {
  return React.createElement("aside", { className: `map-info-card ${info.type}` },
    React.createElement("button", { className: "map-info-close", onClick: onClose, "aria-label": "Cerrar informacion" }, "x"),
    React.createElement("strong", null, info.title),
    info.subtitle && React.createElement("span", null, info.subtitle),
    React.createElement("dl", null,
      info.rows.map(([label, value]: [any, any]) => React.createElement(React.Fragment, { key: label },
        React.createElement("dt", null, label),
        React.createElement("dd", null, value)
      ))
    )
  );
}

function planeSvg(color: string, angle: number) {
  return `
    <svg class="plane-svg" viewBox="-24 -24 48 48" style="transform: rotate(${angle}deg)" aria-hidden="true">
      <path class="plane-halo" d="M0 -22 C5 -22 7 -15 7 -6 L23 6 L23 13 L5 7 L4 17 L9 22 L9 24 L0 20 L-9 24 L-9 22 L-4 17 L-5 7 L-23 13 L-23 6 L-7 -6 C-7 -15 -5 -22 0 -22 Z"></path>
      <path class="plane-body" fill="${color}" d="M0 -22 C5 -22 7 -15 7 -6 L23 6 L23 13 L5 7 L4 17 L9 22 L9 24 L0 20 L-9 24 L-9 22 L-4 17 L-5 7 L-23 13 L-23 6 L-7 -6 C-7 -15 -5 -22 0 -22 Z"></path>
    </svg>
  `;
}

function Timeline({ simMinute, maxMinute, setSimMinute, data }: { simMinute: any; maxMinute: any; setSimMinute: any; data: any }) {
  return React.createElement("div", { className: "timeline" },
    React.createElement("input", {
      type: "range",
      min: "0",
      max: maxMinute,
      value: Math.floor(simMinute),
      onChange: (event) => setSimMinute(Number((event.target as HTMLInputElement).value)),
      disabled: !data,
    }),
    React.createElement("div", { className: "timeline-meta" },
      React.createElement("span", null, "Dia 0 · 00:00"),
      React.createElement("strong", null, formatSimMinute(simMinute)),
      React.createElement("span", null, `Dia ${Math.floor(maxMinute / 1440)} · 00:00`)
    )
  );
}

function AirportDetail({ airport, load }: { airport: any; load: any }) {
  const utilization = airport.maxCapacity ? load / airport.maxCapacity : 0;
  const status = capacityStatus(utilization);
  return React.createElement("div", { className: "metrics" },
    React.createElement(Metric, { label: "Carga actual", value: load, sub: `cap. ${airport.maxCapacity}` }),
    React.createElement(Metric, { label: "Uso actual", value: `${Math.round(utilization * 100)}%`, sub: status.toUpperCase() }),
    React.createElement(Metric, { label: "Pico ALNS", value: airport.peakLoad, sub: `${Math.round(airport.utilization * 100)}%` }),
    React.createElement(Metric, { label: "Ubicacion", value: airport.country, sub: airport.continent })
  );
}

function FlightsTable({ flights }: { flights: any }) {
  if (!flights.length) return React.createElement("div", { className: "empty-state" }, "No hay vuelos activos en este minuto.");
  return React.createElement("div", { className: "table" },
    flights.slice(0, 10).map((flight: any) => React.createElement("div", { className: "row", key: flight.id },
      React.createElement("span", { className: `dot ${flight.status}` }),
      React.createElement("div", { className: "row-main" },
        React.createElement("strong", null, `${flight.origin} -> ${flight.destination}`),
        React.createElement("span", null, `Dia ${flight.dayOffset} · ${hhmm(flight.departureMinute)}-${hhmm(flight.arrivalMinute)}`)
      ),
      React.createElement("span", { className: "capacity-pill", style: { background: STATUS_COLOR[flight.status as keyof typeof STATUS_COLOR] } }, `${Math.round(flight.utilization * 100)}%`)
    ))
  );
}

function AirportsTable({ airports, loads }: { airports: any; loads: any }) {
  const ordered = [...airports].sort((a, b) => (loads[b.code] || 0) / b.maxCapacity - (loads[a.code] || 0) / a.maxCapacity);
  return React.createElement("div", { className: "table" },
    ordered.slice(0, 10).map((airport) => {
      const load = loads[airport.code] || 0;
      const utilization = airport.maxCapacity ? load / airport.maxCapacity : 0;
      const status = capacityStatus(utilization);
      return React.createElement("div", { className: "row", key: airport.code },
        React.createElement("span", { className: `dot ${status}` }),
        React.createElement("div", { className: "row-main" },
          React.createElement("strong", null, `${airport.code} · ${airport.city}`),
          React.createElement("span", null, `${load}/${airport.maxCapacity} maletas`)
        ),
        React.createElement("span", { className: "capacity-pill", style: { background: STATUS_COLOR[status] } }, `${Math.round(utilization * 100)}%`)
      );
    })
  );
}

function computeActiveFlights(data: any, minute: number) {
  if (!data) return [];
  return data.flights
    .filter((flight: any) => minute >= flight.absoluteDepartureMinute && minute <= flight.absoluteArrivalMinute)
    .map((flight: any) => ({
      ...flight,
      progress: clamp((minute - flight.absoluteDepartureMinute) / Math.max(1, flight.absoluteArrivalMinute - flight.absoluteDepartureMinute), 0, 1),
    }));
}

function computeAirportLoads(data: any, minute: number) {
  if (!data) return {};
  const loads = Object.fromEntries(data.airports.map((airport: any) => [airport.code, 0]));
  for (const event of data.airportEvents) {
    if (event.minute > minute) break;
    loads[event.airport] = Math.max(0, (loads[event.airport] || 0) + event.delta);
  }
  return loads;
}

function bearingDegrees(lat1: number, lon1: number, lat2: number, lon2: number) {
  const toRad = (value: number) => value * Math.PI / 180;
  const toDeg = (value: number) => value * 180 / Math.PI;
  const phi1 = toRad(lat1);
  const phi2 = toRad(lat2);
  const deltaLon = toRad(lon2 - lon1);
  const y = Math.sin(deltaLon) * Math.cos(phi2);
  const x = Math.cos(phi1) * Math.sin(phi2) - Math.sin(phi1) * Math.cos(phi2) * Math.cos(deltaLon);
  return (toDeg(Math.atan2(y, x)) + 360) % 360;
}

function capacityStatus(utilization: number) {
  if (utilization < 0.70) return "green";
  if (utilization < 0.90) return "yellow";
  return "red";
}

function compactDate(date: string) {
  return date.replaceAll("-", "");
}

function formatClock(date: Date) {
  return date.toLocaleTimeString("es-PE", { hour: "2-digit", minute: "2-digit", second: "2-digit" });
}

function formatDateOnly(value: string | Date | undefined) {
  if (!value) return "--";
  return new Date(value).toLocaleDateString("es-PE", {
    year: "numeric",
    month: "2-digit",
    day: "2-digit",
  });
}

function formatTimeOnly(value: string | Date | undefined) {
  if (!value) return "--";
  return new Date(value).toLocaleTimeString("es-PE", {
    hour: "2-digit",
    minute: "2-digit",
  });
}

function formatFlightMoment(data: any, absoluteMinute: number) {
  if (!data?.simulationStartDateTime && data?.simulationStartDate) return formatSimMinute(absoluteMinute);
  if (!data?.simulationStartDateTime) return formatSimMinute(absoluteMinute);
  const date = new Date(new Date(data.simulationStartDateTime).getTime() + absoluteMinute * 60000);
  return `${formatDateOnly(date)}, ${formatTimeOnly(date)}`;
}

function formatSimMinute(value: number) {
  const minute = Math.max(0, Math.floor(value));
  const day = Math.floor(minute / 1440);
  const dayMinute = minute % 1440;
  const hour = Math.floor(dayMinute / 60);
  const min = dayMinute % 60;
  return `Dia ${day} · ${String(hour).padStart(2, "0")}:${String(min).padStart(2, "0")}`;
}

function hhmm(value: number) {
  const minute = ((Math.floor(value) % 1440) + 1440) % 1440;
  return `${String(Math.floor(minute / 60)).padStart(2, "0")}:${String(minute % 60).padStart(2, "0")}`;
}

function percent(part: number, total: number) {
  return total ? Math.round((part / total) * 100) : 0;
}

function clamp(value: number, min: number, max: number) {
  return Math.min(max, Math.max(min, value));
}