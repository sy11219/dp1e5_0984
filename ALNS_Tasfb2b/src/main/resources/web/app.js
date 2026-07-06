const { useEffect, useMemo, useRef, useState } = React;

const STATUS_COLOR = {
  green: "#21a67a",
  yellow: "#d9a219",
  red: "#d84545",
};

const BATCH_MINUTES = 180;
const BATCH_INTERVAL_MS = 120_000;
const STATUS_FILTERS = [
  { value: "all", label: "Todos" },
  { value: "green", label: "Verde" },
  { value: "yellow", label: "Amarillo" },
  { value: "red", label: "Rojo" },
];

function App() {
  const [startDate, setStartDate] = useState("2026-07-01");
  const [startTime, setStartTime] = useState("10:00");
  const [days] = useState(5);
  const [data, setData] = useState(null);
  const [simMinute, setSimMinute] = useState(0);
  const [playing, setPlaying] = useState(false);
  const [speed, setSpeed] = useState(360);
  const [loading, setLoading] = useState(false);
  const [batchRunning, setBatchRunning] = useState(false);
  const [batchPhase, setBatchPhase] = useState("");
  const [error, setError] = useState("");
  const [selectedAirport, setSelectedAirport] = useState(null);
  const [airportStatusFilter, setAirportStatusFilter] = useState("all");
  const [flightStatusFilter, setFlightStatusFilter] = useState("all");
  const [now, setNow] = useState(new Date());
  const frame = useRef(null);
  const batchAbortRef = useRef(false);

  const isBatchMode = true;
  const maxMinute = data?.maxTick ?? days * 1440;

  useEffect(() => {
    const timer = setInterval(() => setNow(new Date()), 1000);
    return () => clearInterval(timer);
  }, []);

  useEffect(() => {
    if (!playing || batchRunning) return;
    let last = performance.now();
    const tick = (time) => {
      const elapsedSeconds = (time - last) / 1000;
      last = time;
      setSimMinute((minute) => {
        const next = Math.min(maxMinute, minute + elapsedSeconds * speed);
        if (next >= maxMinute) setPlaying(false);
        return next;
      });
      frame.current = requestAnimationFrame(tick);
    };
    frame.current = requestAnimationFrame(tick);
    return () => cancelAnimationFrame(frame.current);
  }, [playing, speed, maxMinute, batchRunning]);

  useEffect(() => () => { batchAbortRef.current = true; }, []);

  async function runSimulation() {
    if (isBatchMode) {
      await runBatchSimulation();
      return;
    }

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
      setData(payload);
      setSelectedAirport(payload.airports[0]?.code || null);
    } catch (err) {
      setError(err.message);
    } finally {
      setLoading(false);
    }
  }

  async function runBatchSimulation() {
    setLoading(true);
    setError("");
    setPlaying(false);
    setBatchRunning(true);
    setBatchPhase("Iniciando sesion...");
    batchAbortRef.current = false;

    try {
      const startResponse = await fetch("/api/simulations/batch/start", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({
          startDate: compactDate(startDate),
          days: 5,
          startTime,
        }),
      });
      let payload = await startResponse.json();
      if (!startResponse.ok) throw new Error(payload.error || "No se pudo iniciar la Simulacion 5 dias.");

      setData(payload);
      setSelectedAirport(payload.airports[0]?.code || null);
      setSimMinute(payload.startOffsetMinutes ?? payload.tick ?? 0);
      setLoading(false);

      while (!batchAbortRef.current && payload.status === "RUNNING") {
        const cycleStart = performance.now();
        setBatchPhase(`Actualizando simulacion ${formatSimMinute(payload.tick)} → ${formatSimMinute(payload.tick + BATCH_MINUTES)}`);

        const advanceResponse = await fetch(`/api/simulations/batch/${payload.simulationId}/advance`, {
          method: "POST",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify({ steps: BATCH_MINUTES }),
        });
        payload = await advanceResponse.json();
        if (!advanceResponse.ok) throw new Error(payload.error || "No se pudo actualizar la simulacion.");

        setData(payload);

        const batchStart = payload.lastBatchStart ?? Math.max(payload.startOffsetMinutes ?? 0, payload.tick - BATCH_MINUTES);
        const batchEnd = payload.lastBatchEnd ?? payload.tick;
        setSimMinute(batchStart);

        const elapsedBeforeAnim = performance.now() - cycleStart;
        const animDuration = Math.max(5_000, (payload.batchIntervalMs ?? BATCH_INTERVAL_MS) - elapsedBeforeAnim);

        setBatchPhase(`Animando ${formatSimMinute(batchStart)} → ${formatSimMinute(batchEnd)} (${Math.round(animDuration / 1000)} s)`);
        await animateSimWindow(batchStart, batchEnd, animDuration);

        const cycleElapsed = performance.now() - cycleStart;
        const waitMs = (payload.batchIntervalMs ?? BATCH_INTERVAL_MS) - cycleElapsed;
        if (waitMs > 0) {
          await sleep(waitMs);
        }

        if (payload.status === "COMPLETED") {
          setBatchPhase("Simulacion completada.");
          break;
        }
      }
    } catch (err) {
      setError(err.message);
      setBatchPhase("");
    } finally {
      setBatchRunning(false);
      setLoading(false);
    }
  }

  function animateSimWindow(fromMinute, toMinute, durationMs) {
    return new Promise((resolve) => {
      const startedAt = performance.now();

      const step = (now) => {
        if (batchAbortRef.current) {
          resolve();
          return;
        }
        const progress = Math.min(1, (now - startedAt) / durationMs);
        setSimMinute(fromMinute + (toMinute - fromMinute) * progress);
        if (progress < 1) {
          requestAnimationFrame(step);
        } else {
          setSimMinute(toMinute);
          resolve();
        }
      };

      requestAnimationFrame(step);
    });
  }

  function sleep(ms) {
    return new Promise((resolve) => setTimeout(resolve, ms));
  }

  function stopBatchSimulation() {
    batchAbortRef.current = true;
    setBatchRunning(false);
    setBatchPhase("Detenido.");
  }

  const airportLoads = useMemo(() => computeAirportLoads(data, simMinute), [data, simMinute]);
  const activeFlights = useMemo(() => computeActiveFlights(data, simMinute), [data, simMinute]);
  const filteredActiveFlights = useMemo(
    () => activeFlights.filter((flight) => matchesStatusFilter(flight.status, flightStatusFilter)),
    [activeFlights, flightStatusFilter]
  );
  const selected = data?.airports.find((airport) => airport.code === selectedAirport);

  return (
    React.createElement("div", { className: "app-shell" },
      React.createElement(Topbar, { data, now, simMinute, batchPhase, batchRunning }),
      React.createElement("main", { className: "workspace" },
        React.createElement("aside", { className: "side-panel" },
          React.createElement("section", { className: "panel section" },
            React.createElement("h2", null, "Simulacion 5 dias"),
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
                React.createElement("label", null, "Hora inicial"),
                React.createElement("input", {
                  type: "time",
                  value: startTime,
                  disabled: batchRunning,
                  onChange: (event) => setStartTime(event.target.value),
                })
              ),
              React.createElement("div", { className: "field" },
                React.createElement("label", null, "Dias de simulacion"),
                React.createElement("div", { className: "segmented" },
                  React.createElement("button", {
                    className: "active",
                    disabled: true,
                  }, "5 dias")
                )
              ),
              React.createElement("button", {
                className: "primary",
                onClick: runSimulation,
                disabled: loading || batchRunning,
              }, loading
                ? "Preparando simulacion..."
                : "Ejecutar Simulacion 5 dias"),
              batchRunning && React.createElement("button", {
                className: "secondary",
                onClick: stopBatchSimulation,
              }, "Detener"),
              batchPhase && React.createElement("div", { className: "batch-status" }, batchPhase),
              error && React.createElement("div", { className: "error" }, error),
              React.createElement("div", { className: "speed-row" },
                React.createElement("input", {
                  type: "range",
                  min: "60",
                  max: "1800",
                  step: "60",
                  value: speed,
                  disabled: batchRunning,
                  onChange: (event) => setSpeed(Number(event.target.value)),
                }),
                React.createElement("strong", null, batchRunning ? "auto" : `${speed}x`)
              ),
              React.createElement("div", { className: "segmented" },
                React.createElement("button", {
                  onClick: () => setPlaying(true),
                  className: playing ? "active" : "",
                  disabled: batchRunning || !data,
                }, "Play"),
                React.createElement("button", { onClick: () => setPlaying(false), disabled: batchRunning }, "Pausa"),
                React.createElement("button", {
                  onClick: () => {
                    setPlaying(false);
                    setSimMinute(data?.startOffsetMinutes ?? 0);
                  },
                  disabled: batchRunning || !data,
                }, "Reset")
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
            data ? React.createElement(Metrics, { data }) : React.createElement("div", { className: "empty-state" }, "Ejecuta Simulacion 5 dias para ver metricas.")
          )
        ),
        React.createElement("section", { className: "panel map-panel" },
          React.createElement(MapStage, {
            data,
            activeFlights: filteredActiveFlights,
            airportLoads,
            airportStatusFilter,
            flightStatusFilter,
            selectedAirport,
            onSelectAirport: setSelectedAirport,
          }),
          React.createElement(Timeline, { simMinute, maxMinute, setSimMinute, data, disabled: batchRunning })
        ),
        React.createElement("aside", { className: "right-panel" },
          React.createElement("section", { className: "panel section" },
            React.createElement("h3", null, selected ? `${selected.code} · ${selected.city}` : "Aeropuerto"),
            selected && React.createElement(AirportDetail, { airport: selected, load: airportLoads[selected.code] || 0 })
          ),
          React.createElement("section", { className: "panel section" },
            React.createElement("h3", null, "Vuelos activos"),
            React.createElement(StatusFilter, {
              label: "Filtro por color",
              value: flightStatusFilter,
              onChange: setFlightStatusFilter,
            }),
            React.createElement(FlightsTable, { flights: filteredActiveFlights, statusFilter: flightStatusFilter })
          ),
          React.createElement("section", { className: "panel section" },
            React.createElement("h3", null, "Aeropuertos criticos"),
            React.createElement(StatusFilter, {
              label: "Filtro por color",
              value: airportStatusFilter,
              onChange: setAirportStatusFilter,
            }),
            data ? React.createElement(AirportsTable, {
              airports: data.airports,
              loads: airportLoads,
              statusFilter: airportStatusFilter,
            }) : React.createElement("div", { className: "empty-state" }, "Sin datos.")
          )
        )
      )
    )
  );
}

function StatusFilter({ label, value, onChange }) {
  return React.createElement("div", { className: "status-filter" },
    React.createElement("span", { className: "status-filter-label" }, label),
    React.createElement("div", { className: "status-filter-options" },
      STATUS_FILTERS.map((option) => React.createElement("button", {
        key: option.value,
        type: "button",
        className: value === option.value ? "active" : "",
        onClick: () => onChange(option.value),
        "aria-pressed": value === option.value,
      },
        option.value !== "all" && React.createElement("span", { className: `dot ${option.value}` }),
        React.createElement("span", null, option.label)
      ))
    )
  );
}

function Topbar({ data, now, simMinute, batchPhase, batchRunning }) {
  return React.createElement("header", { className: "topbar" },
    React.createElement("div", { className: "brand" },
      React.createElement("strong", null, "TASF.B2B · Simulacion 5 dias"),
      React.createElement("span", null, batchRunning ? "Simulacion 5 dias" : "Tiempo real")
    ),
    React.createElement("div", { className: "status-strip" },
      React.createElement(StatusItem, { label: "Ahora", value: formatClock(now), sub: formatDateOnly(now) }),
      React.createElement(StatusItem, { label: "Reloj simulado", value: formatSimMinute(simMinute), sub: formatWallClock(data, simMinute) }),
      batchRunning && React.createElement(StatusItem, { label: "Actualizacion", value: data?.batchCount ?? 0, sub: batchPhase || "en curso" }),
      React.createElement(StatusItem, { label: "Inicio", value: data ? formatTimeOnly(data.realStartedAt) : "--", sub: data ? formatDateOnly(data.realStartedAt) : "--" }),
      React.createElement(StatusItem, { label: "Ultima actualizacion", value: data ? formatTimeOnly(data.realFinishedAt) : "--", sub: data ? formatDateOnly(data.realFinishedAt) : "--" }),
      React.createElement(StatusItem, { label: "Simulado desde", value: data ? formatDateOnly(data.simulationStartDateTime) : "--", sub: data ? formatTimeOnly(data.simulationStartDateTime) : "--" }),
      React.createElement(StatusItem, { label: "Simulado hasta", value: data ? formatDateOnly(data.simulationEndDateTime) : "--", sub: data ? formatTimeOnly(data.simulationEndDateTime) : "--" }),
      React.createElement(StatusItem, { label: "Duracion", value: data ? `${(data.runtimeMs / 1000).toFixed(2)} s` : "--", sub: "ejecucion real" }),
      React.createElement(StatusItem, { label: "Escenario", value: data?.scenario || "Simulacion 5 dias", sub: "planificacion" })
    )
  );
}

function StatusItem({ label, value, sub }) {
  return React.createElement("div", { className: "status-item" },
    React.createElement("span", null, label),
    React.createElement("strong", null, value),
    sub && React.createElement("small", null, sub)
  );
}

function Metrics({ data }) {
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

function Metric({ label, value, sub }) {
  return React.createElement("div", { className: "metric" },
    React.createElement("span", null, label),
    React.createElement("strong", null, value),
    React.createElement("span", null, sub)
  );
}

function MapStage({ data, activeFlights, airportLoads, airportStatusFilter, flightStatusFilter, selectedAirport, onSelectAirport }) {
  const [mapInfo, setMapInfo] = useState(null);
  const mapElement = useRef(null);
  const mapRef = useRef(null);
  const routeLayerRef = useRef(null);
  const airportLayerRef = useRef(null);
  const planeLayerRef = useRef(null);
  const airportLoadsRef = useRef({});
  const airportMarkersRef = useRef(new Map());
  const airports = data?.airports || [];
  const visibleAirports = useMemo(
    () => airports.filter((airport) => {
      const load = airportLoads[airport.code] || 0;
      const utilization = airport.maxCapacity ? load / airport.maxCapacity : 0;
      return matchesStatusFilter(capacityStatus(utilization), airportStatusFilter);
    }),
    [airports, airportLoads, airportStatusFilter]
  );
  const airportByCode = useMemo(() => Object.fromEntries(airports.map((airport) => [airport.code, airport])), [airports]);
  const usedPairs = useMemo(() => {
    if (!data) return [];
    const seen = new Set();
    return data.flights.filter((flight) => {
      const key = `${flight.origin}-${flight.destination}`;
      if (seen.has(key)) return false;
      seen.add(key);
      return true;
    });
  }, [data]);

  useEffect(() => {
    airportLoadsRef.current = airportLoads;
  }, [airportLoads]);

  useEffect(() => {
    if (!mapElement.current || mapRef.current || !window.L) return;

    const map = L.map(mapElement.current, {
      worldCopyJump: true,
      zoomControl: true,
      preferCanvas: true,
      maxBounds: [[-58, -115], [62, 95]],
      maxBoundsViscosity: 0.4,
    }).setView([10, -5], 2);
    map.createPane("routes");
    map.createPane("activeFlights");
    map.createPane("airports");
    map.getPane("routes").style.zIndex = 430;
    map.getPane("activeFlights").style.zIndex = 620;
    map.getPane("airports").style.zIndex = 660;

    L.tileLayer("https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png", {
      maxZoom: 8,
      minZoom: 2,
      attribution: '&copy; <a href="https://www.openstreetmap.org/copyright">OpenStreetMap</a>',
    }).addTo(map);

    routeLayerRef.current = L.layerGroup().addTo(map);
    airportLayerRef.current = L.layerGroup().addTo(map);
    planeLayerRef.current = L.layerGroup().addTo(map);
    mapRef.current = map;

    [0, 100, 350].forEach((delay) => {
      setTimeout(() => {
        map.invalidateSize();
        map.setView([10, -5], 2, { animate: false });
      }, delay);
    });
    return () => {
      map.remove();
      mapRef.current = null;
    };
  }, []);

  useEffect(() => {
    if (!mapRef.current || !airportLayerRef.current || !window.L) return;

    airportLayerRef.current.clearLayers();
    airportMarkersRef.current.clear();
    for (const airport of visibleAirports) {
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
  }, [visibleAirports, onSelectAirport]);

  useEffect(() => {
    for (const airport of visibleAirports) {
      const item = airportMarkersRef.current.get(airport.code);
      if (!item) continue;
      const load = airportLoads[airport.code] || 0;
      const status = capacityStatus(load / airport.maxCapacity);
      const element = item.marker.getElement()?.querySelector(".airport-marker");
      if (!element) continue;
      element.className = `airport-marker ${status}${airport.code === selectedAirport ? " selected" : ""}`;
    }
  }, [visibleAirports, airportLoads, selectedAirport]);

  useEffect(() => {
    if (!mapRef.current || !planeLayerRef.current || !window.L) return;

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
        html: planeSvg(STATUS_COLOR[flight.status], angle),
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
    if (!data || !mapRef.current || !airports.length || !window.L) return;
    const bounds = L.latLngBounds(airports.map((airport) => [airport.latitude, airport.longitude]));
    setTimeout(() => {
      mapRef.current.invalidateSize();
      mapRef.current.fitBounds(bounds.pad(0.16), { maxZoom: 3, animate: false });
    }, 0);
  }, [data, airports]);

  return React.createElement("div", { className: "map-stage" },
    React.createElement("div", { className: "map-header" },
      React.createElement("span", { className: "badge" }, data ? `${visibleAirports.length}/${data.airports.length} aeropuertos` : "Mapa operativo"),
      React.createElement("span", { className: "badge" }, data ? `${activeFlights.length} vuelos en aire` : "Simulacion"),
      airportStatusFilter !== "all" && React.createElement("span", { className: "badge filter-badge" }, `Almacenes ${filterLabel(airportStatusFilter)}`),
      flightStatusFilter !== "all" && React.createElement("span", { className: "badge filter-badge" }, `Vuelos ${filterLabel(flightStatusFilter)}`)
    ),
    mapInfo && React.createElement(MapInfoCard, { info: mapInfo, onClose: () => setMapInfo(null) }),
    React.createElement("div", { ref: mapElement, className: "leaflet-map", role: "img", "aria-label": "Mapa mundial con aeropuertos y vuelos activos" }),
    !window.L && React.createElement("div", { className: "map-load-error" }, "No se pudo cargar el mapa. Revisa tu conexion para Leaflet/OpenStreetMap.")
  );
}

function MapInfoCard({ info, onClose }) {
  return React.createElement("aside", { className: `map-info-card ${info.type}` },
    React.createElement("button", { className: "map-info-close", onClick: onClose, "aria-label": "Cerrar informacion" }, "x"),
    React.createElement("strong", null, info.title),
    info.subtitle && React.createElement("span", null, info.subtitle),
    React.createElement("dl", null,
      info.rows.map(([label, value]) => React.createElement(React.Fragment, { key: label },
        React.createElement("dt", null, label),
        React.createElement("dd", null, value)
      ))
    )
  );
}

function planeSvg(color, angle) {
  return `
    <svg class="plane-svg" viewBox="-24 -24 48 48" style="transform: rotate(${angle}deg)" aria-hidden="true">
      <path class="plane-halo" d="M0 -22 C5 -22 7 -15 7 -6 L23 6 L23 13 L5 7 L4 17 L9 22 L9 24 L0 20 L-9 24 L-9 22 L-4 17 L-5 7 L-23 13 L-23 6 L-7 -6 C-7 -15 -5 -22 0 -22 Z"></path>
      <path class="plane-body" fill="${color}" d="M0 -22 C5 -22 7 -15 7 -6 L23 6 L23 13 L5 7 L4 17 L9 22 L9 24 L0 20 L-9 24 L-9 22 L-4 17 L-5 7 L-23 13 L-23 6 L-7 -6 C-7 -15 -5 -22 0 -22 Z"></path>
    </svg>
  `;
}

function airportPopup(airport, load, status) {
  const usage = airport.maxCapacity ? Math.round((load / airport.maxCapacity) * 100) : 0;
  return `
    <div class="map-popup">
      <strong>${escapeHtml(airport.code)} - ${escapeHtml(airport.city)}</strong>
      <span>${escapeHtml(airport.country)} / ${escapeHtml(airport.continent)}</span>
      <dl>
        <dt>Carga actual</dt><dd>${load}/${airport.maxCapacity} maletas</dd>
        <dt>Uso actual</dt><dd>${usage}% (${status.toUpperCase()})</dd>
        <dt>Pico registrado</dt><dd>${airport.peakLoad} maletas</dd>
      </dl>
    </div>
  `;
}

function flightPopup(flight, origin, dest, data) {
  return `
    <div class="map-popup">
      <strong>Vuelo ${escapeHtml(flight.id)}</strong>
      <span>${escapeHtml(origin.code)} ${escapeHtml(origin.city)} -> ${escapeHtml(dest.code)} ${escapeHtml(dest.city)}</span>
      <dl>
        <dt>Salida</dt><dd>${formatFlightMoment(data, flight.absoluteDepartureMinute)}</dd>
        <dt>Llegada</dt><dd>${formatFlightMoment(data, flight.absoluteArrivalMinute)}</dd>
        <dt>Avance</dt><dd>${Math.round(flight.progress * 100)}%</dd>
        <dt>Carga</dt><dd>${flight.assignedLoad}/${flight.maxCapacity} maletas (${Math.round(flight.utilization * 100)}%)</dd>
      </dl>
    </div>
  `;
}

function flightRoutePopup(flight, origin, dest, data) {
  return `
    <div class="map-popup">
      <strong>Ruta ${escapeHtml(origin.code)} -> ${escapeHtml(dest.code)}</strong>
      <span>Vuelo ${escapeHtml(flight.id)}</span>
      <dl>
        <dt>Salida</dt><dd>${formatFlightMoment(data, flight.absoluteDepartureMinute)}</dd>
        <dt>Llegada</dt><dd>${formatFlightMoment(data, flight.absoluteArrivalMinute)}</dd>
        <dt>Carga</dt><dd>${flight.assignedLoad}/${flight.maxCapacity} maletas</dd>
      </dl>
    </div>
  `;
}

function Timeline({ simMinute, maxMinute, setSimMinute, data, disabled }) {
  const startOffset = data?.startOffsetMinutes ?? 0;
  return React.createElement("div", { className: "timeline" },
    React.createElement("input", {
      type: "range",
      min: String(startOffset),
      max: maxMinute,
      value: Math.floor(simMinute),
      onChange: (event) => setSimMinute(Number(event.target.value)),
      disabled: !data || disabled,
    }),
    React.createElement("div", { className: "timeline-meta" },
      React.createElement("span", null, formatSimMinute(startOffset)),
      React.createElement("strong", null, formatSimMinute(simMinute)),
      React.createElement("span", null, formatSimMinute(maxMinute))
    )
  );
}

function AirportDetail({ airport, load }) {
  const utilization = airport.maxCapacity ? load / airport.maxCapacity : 0;
  const status = capacityStatus(utilization);
  return React.createElement("div", { className: "metrics" },
    React.createElement(Metric, { label: "Carga actual", value: load, sub: `cap. ${airport.maxCapacity}` }),
    React.createElement(Metric, { label: "Uso actual", value: `${Math.round(utilization * 100)}%`, sub: status.toUpperCase() }),
    React.createElement(Metric, { label: "Pico registrado", value: airport.peakLoad, sub: `${Math.round(airport.utilization * 100)}%` }),
    React.createElement(Metric, { label: "Ubicacion", value: airport.country, sub: airport.continent })
  );
}

function FlightsTable({ flights, statusFilter }) {
  if (!flights.length) {
    const message = statusFilter === "all"
      ? "No hay vuelos activos en este minuto."
      : `No hay vuelos activos ${filterLabel(statusFilter).toLowerCase()} en este minuto.`;
    return React.createElement("div", { className: "empty-state" }, message);
  }
  return React.createElement("div", { className: "table" },
    flights.slice(0, 10).map((flight) => React.createElement("div", { className: "row", key: flight.id },
      React.createElement("span", { className: `dot ${flight.status}` }),
      React.createElement("div", { className: "row-main" },
        React.createElement("strong", null, `${flight.origin} -> ${flight.destination}`),
        React.createElement("span", null, `Dia ${flight.dayOffset} · ${hhmm(flight.departureMinute)}-${hhmm(flight.arrivalMinute)}`)
      ),
      React.createElement("span", { className: "capacity-pill", style: { background: STATUS_COLOR[flight.status] } }, `${Math.round(flight.utilization * 100)}%`)
    ))
  );
}

function AirportsTable({ airports, loads, statusFilter }) {
  const ordered = [...airports]
    .filter((airport) => {
      const load = loads[airport.code] || 0;
      const utilization = airport.maxCapacity ? load / airport.maxCapacity : 0;
      return matchesStatusFilter(capacityStatus(utilization), statusFilter);
    })
    .sort((a, b) => (loads[b.code] || 0) / b.maxCapacity - (loads[a.code] || 0) / a.maxCapacity);
  if (!ordered.length) {
    return React.createElement("div", { className: "empty-state" }, `No hay almacenes ${filterLabel(statusFilter).toLowerCase()}.`);
  }
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

function computeActiveFlights(data, minute) {
  if (!data) return [];
  return data.flights
    .filter((flight) => minute >= flight.absoluteDepartureMinute && minute <= flight.absoluteArrivalMinute)
    .map((flight) => ({
      ...flight,
      progress: clamp((minute - flight.absoluteDepartureMinute) / Math.max(1, flight.absoluteArrivalMinute - flight.absoluteDepartureMinute), 0, 1),
    }));
}

function computeAirportLoads(data, minute) {
  if (!data) return {};
  const loads = Object.fromEntries(data.airports.map((airport) => [airport.code, 0]));
  for (const event of data.airportEvents) {
    if (event.minute > minute) break;
    loads[event.airport] = Math.max(0, (loads[event.airport] || 0) + event.delta);
  }
  return loads;
}

function bearingDegrees(lat1, lon1, lat2, lon2) {
  const toRad = (value) => value * Math.PI / 180;
  const toDeg = (value) => value * 180 / Math.PI;
  const phi1 = toRad(lat1);
  const phi2 = toRad(lat2);
  const deltaLon = toRad(lon2 - lon1);
  const y = Math.sin(deltaLon) * Math.cos(phi2);
  const x = Math.cos(phi1) * Math.sin(phi2) - Math.sin(phi1) * Math.cos(phi2) * Math.cos(deltaLon);
  return (toDeg(Math.atan2(y, x)) + 360) % 360;
}

function capacityStatus(utilization) {
  if (utilization < 0.70) return "green";
  if (utilization < 0.90) return "yellow";
  return "red";
}

function matchesStatusFilter(status, filter) {
  return filter === "all" || status === filter;
}

function filterLabel(filter) {
  const match = STATUS_FILTERS.find((option) => option.value === filter);
  return match ? match.label : "Todos";
}

function compactDate(date) {
  return date.replaceAll("-", "");
}

function formatClock(date) {
  return date.toLocaleTimeString("es-PE", { hour: "2-digit", minute: "2-digit", second: "2-digit" });
}

function formatDateOnly(value) {
  if (!value) return "--";
  return new Date(value).toLocaleDateString("es-PE", {
    year: "numeric",
    month: "2-digit",
    day: "2-digit",
  });
}

function formatTimeOnly(value) {
  if (!value) return "--";
  return new Date(value).toLocaleTimeString("es-PE", {
    hour: "2-digit",
    minute: "2-digit",
  });
}

function formatDateTime(value) {
  if (!value) return "--";
  return new Date(value).toLocaleString("es-PE", {
    year: "numeric",
    month: "2-digit",
    day: "2-digit",
    hour: "2-digit",
    minute: "2-digit",
  });
}

function formatFlightMoment(data, absoluteMinute) {
  if (!data?.simulationStartDateTime && data?.simulationStartDate) return formatSimMinute(absoluteMinute);
  if (!data?.simulationStartDateTime) return formatSimMinute(absoluteMinute);
  const date = new Date(new Date(data.simulationStartDateTime).getTime() + absoluteMinute * 60000);
  return `${formatDateOnly(date)}, ${formatTimeOnly(date)}`;
}

function formatWallClock(data, absoluteMinute) {
  if (!data?.simulationStartDateTime) return "avance actual";
  const date = new Date(new Date(data.simulationStartDateTime).getTime() + absoluteMinute * 60000);
  return `${formatDateOnly(date)} ${formatTimeOnly(date)}`;
}

function formatSimMinute(value) {
  const minute = Math.max(0, Math.floor(value));
  const day = Math.floor(minute / 1440);
  const dayMinute = minute % 1440;
  const hour = Math.floor(dayMinute / 60);
  const min = dayMinute % 60;
  return `Dia ${day} · ${String(hour).padStart(2, "0")}:${String(min).padStart(2, "0")}`;
}

function hhmm(value) {
  const minute = ((Math.floor(value) % 1440) + 1440) % 1440;
  return `${String(Math.floor(minute / 60)).padStart(2, "0")}:${String(minute % 60).padStart(2, "0")}`;
}

function percent(part, total) {
  return total ? Math.round((part / total) * 100) : 0;
}

function clamp(value, min, max) {
  return Math.min(max, Math.max(min, value));
}

function escapeHtml(value) {
  return String(value ?? "")
    .replaceAll("&", "&amp;")
    .replaceAll("<", "&lt;")
    .replaceAll(">", "&gt;")
    .replaceAll('"', "&quot;")
    .replaceAll("'", "&#039;");
}

ReactDOM.createRoot(document.getElementById("root")).render(React.createElement(App));
