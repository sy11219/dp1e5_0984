import { useEffect, useMemo, useRef, useState, useCallback } from "react";
import L from "leaflet";
import type { ActiveFlight, Airport, AirportLoads, MapInfoCard as MapInfo, SimulationData } from "../../../types";
import { STATUS_COLOR, MAP_CONFIG, PANE_Z_INDEX } from "../../../utils/constants";
import { capacityStatus } from "../../../utils/calculations";
import { formatFlightMoment } from "../../../utils/formatters";

const MAP_CENTER: L.LatLngTuple = [MAP_CONFIG.center[0], MAP_CONFIG.center[1]];
const MAP_BOUNDS: L.LatLngBoundsLiteral = [
  [MAP_CONFIG.maxBounds[0][0], MAP_CONFIG.maxBounds[0][1]],
  [MAP_CONFIG.maxBounds[1][0], MAP_CONFIG.maxBounds[1][1]],
];

type MapStageProps = {
  data: SimulationData | null;
  activeFlights: ActiveFlight[];
  airportLoads: AirportLoads;
  selectedAirport: string | null;
  onSelectAirport: (code: string) => void;
};

type AirportMarkerItem = {
  airport: Airport;
  marker: L.Marker;
};

export default function MapStage({
  data,
  activeFlights,
  airportLoads,
  selectedAirport,
  onSelectAirport,
}: MapStageProps) {
  const [mapInfo, setMapInfo] = useState<MapInfo | null>(null);
  const mapElement = useRef<HTMLDivElement>(null);
  const mapRef = useRef<L.Map | null>(null);
  const canvasRef = useRef<HTMLCanvasElement | null>(null);
  const airportLayerRef = useRef<L.LayerGroup | null>(null);
  const airportLoadsRef = useRef<AirportLoads>({});
  const airportMarkersRef = useRef(new Map<string, AirportMarkerItem>());
  const didFitBoundsRef = useRef(false);

  const airports = useMemo(() => data?.airports || [], [data]);
  const airportByCode = useMemo(
    () => Object.fromEntries(airports.map((airport) => [airport.code, airport])),
    [airports]
  );

  useEffect(() => {
    airportLoadsRef.current = airportLoads;
  }, [airportLoads]);

  // Inicializar mapa
  useEffect(() => {
    if (!mapElement.current || mapRef.current) return;

    const map = L.map(mapElement.current, {
      worldCopyJump: MAP_CONFIG.worldCopyJump,
      zoomControl: MAP_CONFIG.zoomControl,
      preferCanvas: MAP_CONFIG.preferCanvas,
      maxBounds: MAP_BOUNDS,
      maxBoundsViscosity: MAP_CONFIG.maxBoundsViscosity,
    }).setView(MAP_CENTER, MAP_CONFIG.zoom);

    mapRef.current = map;

    map.createPane("routes");
    map.createPane("activeFlights");
    map.createPane("airports");

    setPaneZIndex(map, "routes", PANE_Z_INDEX.routes);
    setPaneZIndex(map, "activeFlights", PANE_Z_INDEX.activeFlights);
    setPaneZIndex(map, "airports", PANE_Z_INDEX.airports);

    L.tileLayer("https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png", {
      maxZoom: 8,
      minZoom: 2,
      attribution:
        '&copy; <a href="https://www.openstreetmap.org/copyright">OpenStreetMap</a>',
    }).addTo(map);

    airportLayerRef.current = L.layerGroup().addTo(map);

    // Crear canvas overlay
    const canvas = document.createElement("canvas");
    canvas.style.position = "absolute";
    canvas.style.top = "0";
    canvas.style.left = "0";
    canvas.style.pointerEvents = "none";
    canvas.className = "flight-canvas";
    
    canvas.style.zIndex = PANE_Z_INDEX.activeFlights;
    map.getContainer().appendChild(canvas);
    canvasRef.current = canvas;

    // Función para redimensionar y redibujar canvas
    const updateCanvasSize = () => {
      const size = map.getSize();
      canvas.width = size.x;
      canvas.height = size.y;
    };

    updateCanvasSize();
    map.on("resize", updateCanvasSize);

    const timers = [0, 100, 350].map((delay) =>
      window.setTimeout(() => {
        mapRef.current?.invalidateSize();
      }, delay)
    );

    return () => {
      timers.forEach(window.clearTimeout);
      map.off("resize", updateCanvasSize);
      mapRef.current?.remove();
      mapRef.current = null;
    };
  }, []);

  // Renderizar aeropuertos
  useEffect(() => {
    if (!mapRef.current || !airportLayerRef.current) return;

    airportLayerRef.current.clearLayers();
    airportMarkersRef.current.clear();

    for (const airport of airports) {
      const load = airportLoadsRef.current[airport.code] || 0;
      const marker = L.marker([airport.latitude, airport.longitude], {
        icon: createAirportIcon(airport, load, airport.code === selectedAirport),
        pane: "airports",
        interactive: true,
        keyboard: false,
        zIndexOffset: 900,
      })
        .on("click", () => {
          const currentLoad = airportLoadsRef.current[airport.code] || 0;
          onSelectAirport(airport.code);
          setMapInfo(createAirportInfo(airport, currentLoad));
        })
        .addTo(airportLayerRef.current);

      airportMarkersRef.current.set(airport.code, { airport, marker });
    }
  }, [airports, onSelectAirport, selectedAirport]);

  // Actualizar estado de aeropuertos (colores)
  useEffect(() => {
    for (const airport of airports) {
      const item = airportMarkersRef.current.get(airport.code);
      if (!item) continue;

      const load = airportLoads[airport.code] || 0;
      const status = capacityStatus(load / airport.maxCapacity);
      const element = item.marker.getElement()?.querySelector(".airport-marker");

      if (element) {
        element.className = `airport-marker ${status}${airport.code === selectedAirport ? " selected" : ""}`;
      }
    }
  }, [airports, airportLoads, selectedAirport]);

  // Función para dibujar en canvas
  const drawFlights = useCallback(() => {
    if (!canvasRef.current || !mapRef.current) return;

    const ctx = canvasRef.current.getContext("2d");
    if (!ctx) return;

    // Limpiar canvas
    ctx.clearRect(0, 0, canvasRef.current.width, canvasRef.current.height);

    // Dibujar líneas de rutas
    for (const flight of activeFlights) {
      const origin = airportByCode[flight.origin];
      const destination = airportByCode[flight.destination];
      if (!origin || !destination) continue;

      const { originPixel, destPixel } = getRouteGeometry(mapRef.current, origin, destination, flight.progress);

      ctx.strokeStyle = STATUS_COLOR[flight.status];
      ctx.lineWidth = 2.5;
      ctx.globalAlpha = 0.7;
      ctx.beginPath();
      ctx.moveTo(originPixel.x, originPixel.y);
      ctx.lineTo(destPixel.x, destPixel.y);
      ctx.stroke();
      ctx.globalAlpha = 1;
    }

    // Dibujar aviones
    for (const flight of activeFlights) {
      const origin = airportByCode[flight.origin];
      const destination = airportByCode[flight.destination];
      if (!origin || !destination) continue;

      const { planePixel, angle } = getRouteGeometry(mapRef.current, origin, destination, flight.progress);

      drawPlaneIcon(ctx, planePixel.x, planePixel.y, angle, STATUS_COLOR[flight.status]);
    }
  }, [activeFlights, airportByCode, mapRef]);

  // Redibujar cuando cambian vuelos o mapa se mueve
  useEffect(() => {
    drawFlights();
  }, [activeFlights, drawFlights]);

  useEffect(() => {
    const resizeAndRedraw = () => {
      window.requestAnimationFrame(() => {
        if (!mapRef.current || !canvasRef.current) return;
        mapRef.current.invalidateSize({ pan: false });
        const size = mapRef.current.getSize();
        canvasRef.current.width = size.x;
        canvasRef.current.height = size.y;
        drawFlights();
      });
    };

    window.addEventListener("resize", resizeAndRedraw);
    return () => window.removeEventListener("resize", resizeAndRedraw);
  }, [drawFlights]);

  useEffect(() => {
    if (!mapRef.current) return;

    const redraw = () => drawFlights();
    mapRef.current.on("move", redraw);
    mapRef.current.on("zoom", redraw);
    mapRef.current.on("zoomend", redraw);
    mapRef.current.on("viewreset", redraw);

    return () => {
      mapRef.current?.off("move", redraw);
      mapRef.current?.off("zoom", redraw);
      mapRef.current?.off("zoomend", redraw);
      mapRef.current?.off("viewreset", redraw);
    };
  }, [drawFlights]);

  // Manejar clicks en los aviones sin bloquear pan/zoom del mapa.
  useEffect(() => {
    if (!canvasRef.current || !mapRef.current) return;

    const map = mapRef.current;
    const mapContainer = map.getContainer();

    const handleClick = (e: MouseEvent) => {
      const rect = canvasRef.current!.getBoundingClientRect();
      const x = e.clientX - rect.left;
      const y = e.clientY - rect.top;

      // Buscar avión cerca del click (hitarea de 15px)
      for (const flight of activeFlights) {
        const origin = airportByCode[flight.origin];
        const destination = airportByCode[flight.destination];
        if (!origin || !destination) continue;

        const { planePixel } = getRouteGeometry(map, origin, destination, flight.progress);

        const dist = Math.hypot(x - planePixel.x, y - planePixel.y);
        if (dist < 15) {
          setMapInfo(createFlightInfo(data, flight, origin, destination));
          return;
        }
      }
    };

    mapContainer.addEventListener("click", handleClick);

    return () => {
      mapContainer.removeEventListener("click", handleClick);
    };
  }, [activeFlights, airportByCode, data]);

  useEffect(() => {
    if (!data || !mapRef.current || !airports.length || didFitBoundsRef.current) return;

    const bounds = L.latLngBounds(
      airports.map((airport) => [airport.latitude, airport.longitude])
    );

    window.setTimeout(() => {
      mapRef.current?.invalidateSize();
      mapRef.current?.fitBounds(bounds.pad(0.16), { maxZoom: 3, animate: false });
      didFitBoundsRef.current = true;
    }, 0);
  }, [data, airports]);

  return (
    <div className="map-stage">
      <div className="map-header">
        <span className="badge">{data ? `${data.airports.length} aeropuertos` : "Mapa operativo"}</span>
        <span className="badge">{data ? `${activeFlights.length} vuelos en aire` : "Simulación"}</span>
      </div>
      {mapInfo && <MapInfoCard info={mapInfo} onClose={() => setMapInfo(null)} />}
      <div
        ref={mapElement}
        className="leaflet-map"
        role="img"
        aria-label="Mapa mundial con aeropuertos y vuelos activos"
      />
    </div>
  );
}

function MapInfoCard({ info, onClose }: { info: MapInfo; onClose: () => void }) {
  return (
    <aside className={`map-info-card ${info.type}`}>
      <button className="map-info-close" onClick={onClose} aria-label="Cerrar informacion">
        x
      </button>
      <strong>{info.title}</strong>
      {info.subtitle && <span>{info.subtitle}</span>}
      <dl>
        {info.rows.map(([label, value]) => (
          <div key={label}>
            <dt>{label}</dt>
            <dd>{value}</dd>
          </div>
        ))}
      </dl>
    </aside>
  );
}

function createAirportIcon(airport: Airport, load: number, isSelected: boolean) {
  const status = capacityStatus(load / airport.maxCapacity);

  return L.divIcon({
    className: "airport-map-icon",
    html: `<div class="airport-marker ${status}${isSelected ? " selected" : ""}" title="${airport.code} - ${airport.city}"><span></span><strong>${airport.code}</strong></div>`,
    iconSize: [70, 30],
    iconAnchor: [12, 15],
  });
}

function createAirportInfo(airport: Airport, load: number): MapInfo {
  return {
    type: "airport",
    title: `${airport.code} - ${airport.city}`,
    subtitle: `${airport.country} / ${airport.continent}`,
    rows: [
      ["Codigo", airport.code],
      ["Latitud", `${airport.latitude.toFixed(4)} deg`],
      ["Longitud", `${airport.longitude.toFixed(4)} deg`],
      ["Maletas", `${load}/${airport.maxCapacity}`],
      ["Uso", `${Math.round((load / airport.maxCapacity) * 100)}%`],
    ],
  };
}

function createFlightInfo(
  data: SimulationData | null,
  flight: ActiveFlight,
  origin: Airport,
  destination: Airport
): MapInfo {
  return {
    type: "flight",
    title: `Vuelo ${flight.id}`,
    subtitle: `${origin.code} ${origin.city} -> ${destination.code} ${destination.city}`,
    rows: [
      ["Codigo", flight.id],
      ["Origen", `${origin.code} - ${origin.city}`],
      ["Destino", `${destination.code} - ${destination.city}`],
      ["Salida", formatFlightMoment(data, flight.absoluteDepartureMinute)],
      ["Llegada", formatFlightMoment(data, flight.absoluteArrivalMinute)],
      ["Avance", `${Math.round(flight.progress * 100)}%`],
      [
        "Carga",
        `${flight.assignedLoad}/${flight.maxCapacity} maletas (${Math.round(flight.utilization * 100)}%)`,
      ],
    ],
  };
}

function setPaneZIndex(map: L.Map, paneName: string, zIndex: string) {
  const pane = map.getPane(paneName);
  if (pane) pane.style.zIndex = zIndex;
}

function getRouteGeometry(map: L.Map, origin: Airport, destination: Airport, progress: number) {
  const originPixel = map.latLngToContainerPoint([origin.latitude, origin.longitude]);
  const destPixel = map.latLngToContainerPoint([destination.latitude, destination.longitude]);
  const dx = destPixel.x - originPixel.x;
  const dy = destPixel.y - originPixel.y;

  return {
    originPixel,
    destPixel,
    planePixel: L.point(originPixel.x + dx * progress, originPixel.y + dy * progress),
    angle: (Math.atan2(dx, -dy) * 180) / Math.PI,
  };
}

function drawPlaneIcon(ctx: CanvasRenderingContext2D, x: number, y: number, angle: number, color: string) {
  ctx.save();
  ctx.translate(x, y);
  ctx.rotate((angle * Math.PI) / 180);
  ctx.scale(0.54, 0.54);

  drawPlanePath(ctx);
  ctx.strokeStyle = "rgba(255, 255, 255, 0.95)";
  ctx.lineWidth = 5.5;
  ctx.lineJoin = "round";
  ctx.stroke();

  drawPlanePath(ctx);
  ctx.fillStyle = color;
  ctx.strokeStyle = "rgba(255, 255, 255, 0.9)";
  ctx.lineWidth = 1.8;
  ctx.lineJoin = "round";
  ctx.fill();
  ctx.stroke();

  ctx.restore();
  return;

  // Dibujar cuerpo del avión (triangulo apuntando arriba)
  ctx.fillStyle = color;
  ctx.beginPath();
  ctx.moveTo(0, -10); // Punta
  ctx.lineTo(-6, 8);   // Cola izquierda
  ctx.lineTo(0, 4);    // Centro cola
  ctx.lineTo(6, 8);    // Cola derecha
  ctx.closePath();
  ctx.fill();

  // Outline
  ctx.strokeStyle = "rgba(255, 255, 255, 0.8)";
  ctx.lineWidth = 1.5;
  ctx.stroke();

  // Halo (sombra)
  ctx.strokeStyle = "rgba(255, 255, 255, 0.3)";
  ctx.lineWidth = 3;
  ctx.stroke();

  ctx.restore();
}

function drawPlanePath(ctx: CanvasRenderingContext2D) {
  ctx.beginPath();
  ctx.moveTo(0, -22);
  ctx.bezierCurveTo(5, -22, 7, -15, 7, -6);
  ctx.lineTo(23, 6);
  ctx.lineTo(23, 13);
  ctx.lineTo(5, 7);
  ctx.lineTo(4, 17);
  ctx.lineTo(9, 22);
  ctx.lineTo(9, 24);
  ctx.lineTo(0, 20);
  ctx.lineTo(-9, 24);
  ctx.lineTo(-9, 22);
  ctx.lineTo(-4, 17);
  ctx.lineTo(-5, 7);
  ctx.lineTo(-23, 13);
  ctx.lineTo(-23, 6);
  ctx.lineTo(-7, -6);
  ctx.bezierCurveTo(-7, -15, -5, -22, 0, -22);
  ctx.closePath();
}
