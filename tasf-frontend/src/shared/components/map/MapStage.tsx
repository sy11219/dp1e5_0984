import { useEffect, useMemo, useRef, useState, useCallback } from "react";
import type { ReactNode } from "react";
import L from "leaflet";
import type { ActiveFlight, Airport, AirportLoads, CapacityStatus, Flight, MapInfoCard as MapInfo, Shipment, SimulationData } from "../../../features/simulation/types";
import { STATUS_COLOR, MAP_CONFIG, PANE_Z_INDEX } from "../../../features/simulation/utils/constants";
import { capacityStatus } from "../../../features/simulation/utils/calculations";
import { formatFlightMoment } from "../../../features/simulation/utils/formatters";
import { resolveShipmentRoute, type ShipmentRouteLeg } from "../../../features/simulation/utils/shipmentRoute";

const MAP_CENTER: L.LatLngTuple = [MAP_CONFIG.center[0], MAP_CONFIG.center[1]];
const MAP_BOUNDS: L.LatLngBoundsLiteral = [
  [MAP_CONFIG.maxBounds[0][0], MAP_CONFIG.maxBounds[0][1]],
  [MAP_CONFIG.maxBounds[1][0], MAP_CONFIG.maxBounds[1][1]],
];

type MapStageProps = {
  data: SimulationData | null;
  activeFlights: ActiveFlight[];
  airportLoads: AirportLoads;
  airportColorFilter?: "Todos" | CapacityStatus;
  selectedAirport: string | null;
  selectedFlightId?: string | null;
  selectedShipment?: Shipment | null;
  focusTarget?: MapFocusTarget | null;
  displayGmtOffset?: number;
  onSelectAirport: (code: string) => void;
  onSelectFlight?: (id: string) => void;
  onClearSelection?: () => void;
  children?: ReactNode;
};

type AirportMarkerItem = {
  airport: Airport;
  marker: L.Marker;
};

export type MapFocusTarget = {
  type: "airport" | "flight" | "shipment";
  id: string;
  token: number;
};

const FOCUS_ZOOM = 5;
const FLIGHT_ROUTE_LINE_WIDTH = 1.2;
const SELECTED_FLIGHT_ROUTE_LINE_WIDTH = 3.2;

export default function MapStage({
  data,
  activeFlights,
  airportLoads,
  airportColorFilter = "Todos",
  selectedAirport,
  selectedFlightId,
  selectedShipment,
  focusTarget,
  displayGmtOffset,
  onSelectAirport,
  onSelectFlight,
  onClearSelection,
  children,
}: MapStageProps) {
  const [mapInfo, setMapInfo] = useState<MapInfo | null>(null);
  const mapElement = useRef<HTMLDivElement>(null);
  const mapRef = useRef<L.Map | null>(null);
  const canvasRef = useRef<HTMLCanvasElement | null>(null);
  const airportLayerRef = useRef<L.LayerGroup | null>(null);
  const airportLoadsRef = useRef<AirportLoads>({});
  const airportMarkersRef = useRef(new Map<string, AirportMarkerItem>());
  const didFitBoundsRef = useRef(false);
  const lastDrawKeyRef = useRef("");
  const autoCloseTimerRef = useRef<number | null>(null);
  const autoCloseFlightIdRef = useRef<string | null>(null);

  const airports = useMemo(() => data?.airports || [], [data]);
  const visibleAirports = useMemo(
    () =>
      airports.filter((airport) => {
        if (airportColorFilter === "Todos") return true;
        const load = airportLoads[airport.code] || 0;
        return capacityStatus(load / airport.maxCapacity) === airportColorFilter;
      }),
    [airportColorFilter, airportLoads, airports]
  );
  const activeAirports = useMemo(
    () => visibleAirports.filter(isAirportActive),
    [visibleAirports]
  );
  const airportByCode = useMemo(
    () => Object.fromEntries(airports.map((airport) => [airport.code, airport])),
    [airports]
  );
  const selectedShipmentRoute = useMemo(
    () => resolveShipmentRoute(selectedShipment, data?.flights ?? []),
    [data?.flights, selectedShipment]
  );
  const selectedFlight = useMemo(
    () =>
      activeFlights.find((flight) => flight.id === selectedFlightId) ??
      data?.flights.find((flight) => flight.id === selectedFlightId) ??
      null,
    [activeFlights, data?.flights, selectedFlightId]
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

    for (const airport of visibleAirports) {
      const load = airportLoadsRef.current[airport.code] || 0;
      const marker = L.marker([airport.latitude, airport.longitude], {
        icon: createAirportIcon(airport, load, airport.code === selectedAirport),
        pane: "airports",
        interactive: true,
        keyboard: false,
        zIndexOffset: 900,
      })
        .on("click", (event) => {
          L.DomEvent.stopPropagation(event);
          const currentLoad = airportLoadsRef.current[airport.code] || 0;
          onSelectAirport(airport.code);
          setMapInfo(createAirportInfo(airport, currentLoad));
        })
        .addTo(airportLayerRef.current);

      airportMarkersRef.current.set(airport.code, { airport, marker });
    }
  }, [onSelectAirport, selectedAirport, visibleAirports]);

  // Actualizar estado de aeropuertos (colores)
  useEffect(() => {
    for (const airport of visibleAirports) {
      const item = airportMarkersRef.current.get(airport.code);
      if (!item) continue;

      const load = airportLoads[airport.code] || 0;
      const status = capacityStatus(load / airport.maxCapacity);
      const element = item.marker.getElement()?.querySelector(".airport-marker");

      if (element) {
        element.className = `airport-marker ${status}${airport.code === selectedAirport ? " selected" : ""}`;
      }
    }
  }, [airportLoads, selectedAirport, visibleAirports]);

  // Función para dibujar en canvas
  const drawFlights = useCallback((force = false) => {
    if (!canvasRef.current || !mapRef.current) return;

    const ctx = canvasRef.current.getContext("2d");
    if (!ctx) return;

    const drawKey = [
      canvasRef.current.width,
      canvasRef.current.height,
      selectedFlightId ?? "",
      selectedShipment?.id ?? "",
      selectedShipmentRoute
        .map((leg) => `${leg.flightId}:${leg.absoluteDepartureMinute ?? "x"}:${leg.absoluteArrivalMinute ?? "x"}`)
        .join("|"),
      activeFlights
        .map((flight) => `${flight.id}:${Math.round(flight.progress * 1000)}:${flight.status}:${flight.assignedLoad}`)
        .join("|"),
    ].join(":");

    if (!force && drawKey === lastDrawKeyRef.current) return;
    lastDrawKeyRef.current = drawKey;

    // Limpiar canvas
    ctx.clearRect(0, 0, canvasRef.current.width, canvasRef.current.height);

    ctx.lineCap = "round";
    ctx.lineJoin = "round";

    if (selectedShipmentRoute.length > 0) {
      drawShipmentRoute(ctx, mapRef.current, selectedShipmentRoute, airportByCode);
    } else if (selectedFlight && !activeFlights.some((flight) => flight.id === selectedFlight.id)) {
      drawStaticFlightRoute(ctx, mapRef.current, selectedFlight, airportByCode);
    }

    for (const flight of activeFlights) {
      const origin = airportByCode[flight.origin];
      const destination = airportByCode[flight.destination];
      if (!origin || !destination) continue;

      const { destPixel, planePixel, angle } =
        getRouteGeometry(mapRef.current, origin, destination, flight.progress);
      const isSelected = flight.id === selectedFlightId;
      const flightColor = flight.assignedLoad <= 0 ? STATUS_COLOR.gray : STATUS_COLOR[flight.status];

      ctx.strokeStyle = flightColor;
      ctx.lineWidth = isSelected ? SELECTED_FLIGHT_ROUTE_LINE_WIDTH : FLIGHT_ROUTE_LINE_WIDTH;
      ctx.globalAlpha = isSelected ? 0.95 : 0.62;
      ctx.shadowColor = isSelected ? "rgba(15, 23, 42, 0.36)" : "transparent";
      ctx.shadowBlur = isSelected ? 10 : 0;
      ctx.beginPath();
      ctx.moveTo(planePixel.x, planePixel.y);
      ctx.lineTo(destPixel.x, destPixel.y);
      ctx.stroke();
      ctx.globalAlpha = 1;
      ctx.shadowBlur = 0;
      ctx.shadowColor = "transparent";

      drawPlaneIcon(ctx, planePixel.x, planePixel.y, angle, flightColor, isSelected);
    }
  }, [
    activeFlights,
    airportByCode,
    selectedFlight,
    selectedFlightId,
    selectedShipment?.id,
    selectedShipmentRoute,
  ]);

  // Redibujar cuando cambian vuelos o mapa se mueve
  useEffect(() => {
    drawFlights();
  }, [activeFlights, drawFlights]);

  useEffect(() => {
    if (!mapRef.current || !focusTarget?.id) return;

    const map = mapRef.current;
    if (focusTarget.type === "airport") {
      const airport = airportByCode[focusTarget.id];
      if (!airport) return;

      map.flyTo([airport.latitude, airport.longitude], Math.max(map.getZoom(), FOCUS_ZOOM), {
        animate: true,
        duration: 0.75,
      });
      return;
    }

    if (focusTarget.type === "shipment") {
      if (!selectedShipment || selectedShipmentRoute.length === 0) return;

      const routeAirports = selectedShipmentRoute
        .flatMap((leg) => [airportByCode[leg.origin], airportByCode[leg.destination]])
        .filter(Boolean) as Airport[];
      if (!routeAirports.length) return;

      const bounds = L.latLngBounds(
        routeAirports.map((airport) => [airport.latitude, airport.longitude])
      );
      map.fitBounds(bounds.pad(0.25), { maxZoom: FOCUS_ZOOM, animate: true });
      setMapInfo(createShipmentInfo(data, selectedShipment, selectedShipmentRoute));
      return;
    }

    const flight = selectedFlight?.id === focusTarget.id ? selectedFlight : null;
    if (!flight) return;

    const origin = airportByCode[flight.origin];
    const destination = airportByCode[flight.destination];
    if (!origin || !destination) return;

    const progress =
      "progress" in flight && typeof flight.progress === "number"
        ? flight.progress
        : 0.5;
    const lat = origin.latitude + (destination.latitude - origin.latitude) * progress;
    const lng = origin.longitude + (destination.longitude - origin.longitude) * progress;
    map.flyTo([lat, lng], Math.max(map.getZoom(), FOCUS_ZOOM), {
      animate: true,
      duration: 0.75,
    });
    setMapInfo(createFlightInfo(data, flight, origin, destination, displayGmtOffset));
  }, [airportByCode, data, focusTarget, selectedFlight, selectedShipment, selectedShipmentRoute]);

  useEffect(() => {
    const resizeAndRedraw = () => {
      window.requestAnimationFrame(() => {
        if (!mapRef.current || !canvasRef.current) return;
        mapRef.current.invalidateSize({ pan: false });
        const size = mapRef.current.getSize();
        canvasRef.current.width = size.x;
        canvasRef.current.height = size.y;
        drawFlights(true);
      });
    };

    window.addEventListener("resize", resizeAndRedraw);
    return () => window.removeEventListener("resize", resizeAndRedraw);
  }, [drawFlights]);

  useEffect(() => {
    if (!mapRef.current) return;

    const redraw = () => drawFlights(true);
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
      const target = e.target as HTMLElement | null;

      if (target?.closest(".airport-marker, .leaflet-control")) return;

      // Buscar avión cerca del click (hitarea de 15px)
      for (const flight of activeFlights) {
        const origin = airportByCode[flight.origin];
        const destination = airportByCode[flight.destination];
        if (!origin || !destination) continue;

        const { planePixel } = getRouteGeometry(map, origin, destination, flight.progress);

        const dist = Math.hypot(x - planePixel.x, y - planePixel.y);
        if (dist < 15) {
          onSelectFlight?.(flight.id);
          setMapInfo(createFlightInfo(data, flight, origin, destination, displayGmtOffset));
          return;
        }
      }

      onClearSelection?.();
      setMapInfo(null);
    };

    mapContainer.addEventListener("click", handleClick);

    return () => {
      mapContainer.removeEventListener("click", handleClick);
    };
  }, [activeFlights, airportByCode, data, displayGmtOffset, onClearSelection, onSelectFlight]);

  useEffect(() => {
    const clearAutoCloseTimer = () => {
      if (autoCloseTimerRef.current) {
        window.clearTimeout(autoCloseTimerRef.current);
        autoCloseTimerRef.current = null;
      }
      autoCloseFlightIdRef.current = null;
    };

    if (mapInfo?.type !== "flight" || !mapInfo.id) {
      clearAutoCloseTimer();
      return;
    }

    if (selectedFlightId === mapInfo.id) {
      clearAutoCloseTimer();
      return;
    }

    const stillActive = activeFlights.some((flight) => flight.id === mapInfo.id);
    if (stillActive) {
      clearAutoCloseTimer();
      return;
    }

    if (autoCloseFlightIdRef.current === mapInfo.id && autoCloseTimerRef.current) return;

    clearAutoCloseTimer();
    autoCloseFlightIdRef.current = mapInfo.id;
    autoCloseTimerRef.current = window.setTimeout(() => {
      setMapInfo((current) =>
        current?.type === "flight" && current.id === mapInfo.id ? null : current
      );
      if (selectedFlightId === mapInfo.id) onClearSelection?.();
      autoCloseTimerRef.current = null;
      autoCloseFlightIdRef.current = null;
    }, 5000);
  }, [activeFlights, mapInfo, onClearSelection, selectedFlightId]);

  useEffect(() => {
    return () => {
      if (autoCloseTimerRef.current) {
        window.clearTimeout(autoCloseTimerRef.current);
        autoCloseTimerRef.current = null;
      }
      autoCloseFlightIdRef.current = null;
    };
  }, []);

  useEffect(() => {
    if (!data || !mapRef.current || !visibleAirports.length || didFitBoundsRef.current) return;

    const airportsForBounds = activeAirports.length ? activeAirports : visibleAirports;
    const bounds = L.latLngBounds(
      airportsForBounds.map((airport) => [airport.latitude, airport.longitude])
    );

    window.setTimeout(() => {
      mapRef.current?.invalidateSize();
      mapRef.current?.fitBounds(bounds.pad(0.05), {
        maxZoom: 3,
        animate: false,
        padding: [16, 16],
      });
      didFitBoundsRef.current = true;
    }, 0);
  }, [data, visibleAirports, activeAirports]);

  return (
    <div className="map-stage">
      <div className="map-header">
        <span className="badge">{data ? `${visibleAirports.length}/${data.airports.length} aeropuertos` : "Mapa operativo"}</span>
        <span className="badge">{data ? `${activeFlights.length} vuelos en aire` : "Simulación"}</span>
      </div>
      {mapInfo && <MapInfoCard info={mapInfo} onClose={() => setMapInfo(null)} />}
      <div
        ref={mapElement}
        className="leaflet-map"
        role="img"
        aria-label="Mapa mundial con aeropuertos y vuelos activos"
      />
      {children}
    </div>
  );
}

function isAirportActive(airport: Airport) {
  if (typeof airport.active === "boolean") return airport.active;
  return (airport.operationalStatus ?? "ACTIVE").toUpperCase() !== "INACTIVE";
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
    html: `<div class="airport-marker ${status}${isSelected ? " selected" : ""}" title="${airport.code} - ${airport.city}"><span class="airport-warehouse-icon"></span><strong>${airport.code}</strong></div>`,
    iconSize: [70, 30],
    iconAnchor: [12, 15],
  });
}

function createAirportInfo(airport: Airport, load: number): MapInfo {
  return {
    type: "airport",
    id: airport.code,
    title: `${airport.code} - ${airport.city}`,
    subtitle: `${airport.country} / ${airport.continent}`,
    rows: [
      ["Código", airport.code],
      ["Latitud", `${airport.latitude.toFixed(4)} deg`],
      ["Longitud", `${airport.longitude.toFixed(4)} deg`],
      ["Maletas", `${load}/${airport.maxCapacity}`],
      ["Uso", `${Math.round((load / airport.maxCapacity) * 100)}%`],
    ],
  };
}

function createFlightInfo(
  data: SimulationData | null,
  flight: Flight | ActiveFlight,
  origin: Airport,
  destination: Airport,
  displayGmtOffset?: number
): MapInfo {
  const progress = "progress" in flight ? `${Math.round(flight.progress * 100)}%` : "Planificado";
  return {
    type: "flight",
    id: flight.id,
    title: `Vuelo ${flight.id}`,
    subtitle: `${origin.code} ${origin.city} -> ${destination.code} ${destination.city}`,
    rows: [
      ["Código", flight.id],
      ["Origen", `${origin.code} - ${origin.city}`],
      ["Destino", `${destination.code} - ${destination.city}`],
      ["Salida", formatFlightMoment(data, flight.absoluteDepartureMinute, displayGmtOffset)],
      ["Llegada", formatFlightMoment(data, flight.absoluteArrivalMinute, displayGmtOffset)],
      ["Avance", progress],
      [
        "Carga",
        `${flight.assignedLoad}/${flight.maxCapacity} maletas (${Math.round(flight.utilization * 100)}%)`,
      ],
    ],
  };
}

function createShipmentInfo(
  data: SimulationData | null,
  shipment: Shipment,
  route: ShipmentRouteLeg[]
): MapInfo {
  const firstDeparture = route[0]?.absoluteDepartureMinute;
  const lastArrival = [...route].reverse().find((leg) => leg.absoluteArrivalMinute !== undefined)
    ?.absoluteArrivalMinute;
  const scaleCount = Math.max(0, route.length - 1);

  return {
    type: "shipment",
    id: shipment.id,
    title: `Envio ${shipment.id}`,
    subtitle: `${shipment.origin} -> ${shipment.destination}`,
    rows: [
      ["Maletas", shipment.suitcases],
      ["Vuelos", route.length],
      ["Escalas", scaleCount],
      ["Pedido", formatFlightMoment(data, shipment.requestMinute)],
      ["Primera salida", firstDeparture !== undefined ? formatFlightMoment(data, firstDeparture) : "--"],
      ["Llegada estimada", lastArrival !== undefined ? formatFlightMoment(data, lastArrival) : "--"],
      ["Estado", shipment.planned ? (shipment.onTime ? "A tiempo" : "Con retraso") : "Sin ruta"],
    ],
  };
}

function drawShipmentRoute(
  ctx: CanvasRenderingContext2D,
  map: L.Map,
  route: ShipmentRouteLeg[],
  airportByCode: Record<string, Airport>
) {
  route.forEach((leg, index) => {
    const origin = airportByCode[leg.origin];
    const destination = airportByCode[leg.destination];
    if (!origin || !destination) return;

    drawRouteSegment(ctx, map, origin, destination, {
      color: "#f59e0b",
      width: 4.2,
      alpha: 0.86,
      dash: index % 2 === 0 ? [10, 6] : [4, 5],
      shadow: true,
    });
    drawRouteNode(ctx, map, origin, index === 0 ? "#22c55e" : "#f59e0b");
    drawRouteNode(ctx, map, destination, index === route.length - 1 ? "#ef4444" : "#f59e0b");
  });
}

function drawStaticFlightRoute(
  ctx: CanvasRenderingContext2D,
  map: L.Map,
  flight: Flight,
  airportByCode: Record<string, Airport>
) {
  const origin = airportByCode[flight.origin];
  const destination = airportByCode[flight.destination];
  if (!origin || !destination) return;

  drawRouteSegment(ctx, map, origin, destination, {
    color: STATUS_COLOR[flight.status] ?? "#111827",
    width: 3.2,
    alpha: 0.82,
    dash: [8, 6],
    shadow: true,
  });
  drawRouteNode(ctx, map, origin, "#111827");
  drawRouteNode(ctx, map, destination, "#111827");
}

function drawRouteSegment(
  ctx: CanvasRenderingContext2D,
  map: L.Map,
  origin: Airport,
  destination: Airport,
  options: { color: string; width: number; alpha: number; dash?: number[]; shadow?: boolean }
) {
  const { originPixel, destPixel } = getRouteGeometry(map, origin, destination, 0);
  ctx.save();
  ctx.strokeStyle = options.color;
  ctx.lineWidth = options.width;
  ctx.globalAlpha = options.alpha;
  ctx.setLineDash(options.dash ?? []);
  if (options.shadow) {
    ctx.shadowColor = "rgba(15, 23, 42, 0.32)";
    ctx.shadowBlur = 10;
  }
  ctx.beginPath();
  ctx.moveTo(originPixel.x, originPixel.y);
  ctx.lineTo(destPixel.x, destPixel.y);
  ctx.stroke();
  ctx.restore();
}

function drawRouteNode(
  ctx: CanvasRenderingContext2D,
  map: L.Map,
  airport: Airport,
  color: string
) {
  const point = map.latLngToContainerPoint([airport.latitude, airport.longitude]);
  ctx.save();
  ctx.fillStyle = color;
  ctx.strokeStyle = "rgba(255, 255, 255, 0.95)";
  ctx.lineWidth = 2;
  ctx.beginPath();
  ctx.arc(point.x, point.y, 5, 0, Math.PI * 2);
  ctx.fill();
  ctx.stroke();
  ctx.restore();
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

function drawPlaneIcon(
  ctx: CanvasRenderingContext2D,
  x: number,
  y: number,
  angle: number,
  color: string,
  selected = false
) {
  ctx.save();
  ctx.translate(x, y);
  ctx.rotate((angle * Math.PI) / 180);
  ctx.scale(selected ? 0.68 : 0.54, selected ? 0.68 : 0.54);

  if (selected) {
    ctx.shadowColor = "rgba(15, 23, 42, 0.46)";
    ctx.shadowBlur = 14;
    ctx.shadowOffsetY = 3;
  }

  drawPlanePath(ctx);
  ctx.strokeStyle = "rgba(255, 255, 255, 0.95)";
  ctx.lineWidth = selected ? 7 : 5.5;
  ctx.lineJoin = "round";
  ctx.stroke();

  ctx.shadowBlur = 0;
  ctx.shadowOffsetY = 0;
  drawPlanePath(ctx);
  ctx.fillStyle = color;
  ctx.strokeStyle = selected ? "#111827" : "rgba(255, 255, 255, 0.9)";
  ctx.lineWidth = selected ? 2.4 : 1.8;
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
