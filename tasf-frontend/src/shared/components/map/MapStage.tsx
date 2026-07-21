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
  airportPeakLoads?: AirportLoads;
  airportColorFilter?: "Todos" | CapacityStatus;
  flightColorFilter?: "Todos" | CapacityStatus;
  selectedAirport: string | null;
  selectedFlightId?: string | null;
  selectedShipment?: Shipment | null;
  focusTarget?: MapFocusTarget | null;
  resetViewToken?: number;
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
const FLIGHT_ROUTE_LINE_WIDTH = 0.75;
const SELECTED_FLIGHT_ROUTE_LINE_WIDTH = 2.2;
const MAP_MIN_ZOOM = 2;
const MAP_MAX_ZOOM = 8;
const MAP_ZOOM_STEP = 0.01;
const MAP_BUTTON_ZOOM_STEP = 0.25;
const DEFAULT_BOUNDS_PADDING: L.PointExpression = [40, 12];
const DEFAULT_MAP_TILES_URL = "https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png";
const DEFAULT_MAP_TILES_ATTRIBUTION =
  '&copy; <a href="https://www.openstreetmap.org/copyright">OpenStreetMap</a>';
const SPANISH_MAP_TILES_URL = "/api/map/tiles/{z}/{x}/{y}.png";
const SPANISH_MAP_TILES_ATTRIBUTION =
  '&copy; <a href="https://www.maptilesapi.com/">MapTiles API</a> &copy; <a href="https://www.openstreetmap.org/copyright">OpenStreetMap</a>';

export default function MapStage({
  data,
  activeFlights,
  airportLoads,
  airportPeakLoads = {},
  airportColorFilter = "Todos",
  flightColorFilter = "Todos",
  selectedAirport,
  selectedFlightId,
  selectedShipment,
  focusTarget,
  resetViewToken,
  displayGmtOffset,
  onSelectAirport,
  onSelectFlight,
  onClearSelection,
  children,
}: MapStageProps) {
  const [mapInfo, setMapInfo] = useState<MapInfo | null>(null);
  const [zoom, setZoom] = useState<number>(MAP_CONFIG.zoom);
  const mapElement = useRef<HTMLDivElement>(null);
  const mapRef = useRef<L.Map | null>(null);
  const canvasRef = useRef<HTMLCanvasElement | null>(null);
  const canvasOriginRef = useRef<L.Point>(L.point(0, 0));
  const airportLayerRef = useRef<L.LayerGroup | null>(null);
  const airportLoadsRef = useRef<AirportLoads>({});
  const airportPeakLoadsRef = useRef<AirportLoads>({});
  const airportMarkersRef = useRef(new Map<string, AirportMarkerItem>());
  const onSelectAirportRef = useRef(onSelectAirport);
  const didFitBoundsRef = useRef(false);
  const lastResetViewTokenRef = useRef(resetViewToken ?? 0);
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
    onSelectAirportRef.current = onSelectAirport;
  }, [onSelectAirport]);

  useEffect(() => {
    airportLoadsRef.current = airportLoads;
  }, [airportLoads]);

  useEffect(() => {
    airportPeakLoadsRef.current = airportPeakLoads;
  }, [airportPeakLoads]);

  const fitDefaultAirportBounds = useCallback((animate = false) => {
    const map = mapRef.current;
    const airportsForBounds = activeAirports.length ? activeAirports : visibleAirports;
    if (!map || !airportsForBounds.length) return;

    const bounds = L.latLngBounds(
      airportsForBounds.map((airport) => [airport.latitude, airport.longitude])
    );

    map.invalidateSize();
    map.fitBounds(bounds.pad(0.01), {
      animate,
      padding: DEFAULT_BOUNDS_PADDING,
    });
    setZoom(Number(map.getZoom().toFixed(2)));
  }, [activeAirports, visibleAirports]);

  const handleCloseMapInfo = useCallback(() => {
    setMapInfo((current) => {
      if (current?.type === "flight" && selectedFlightId === current.id) {
        onClearSelection?.();
      }
      return null;
    });
  }, [onClearSelection, selectedFlightId]);

  // Inicializar mapa
  useEffect(() => {
    if (!mapElement.current || mapRef.current) return;

    const map = L.map(mapElement.current, {
      worldCopyJump: MAP_CONFIG.worldCopyJump,
      zoomControl: false,
      preferCanvas: MAP_CONFIG.preferCanvas,
      minZoom: MAP_MIN_ZOOM,
      maxZoom: MAP_MAX_ZOOM,
      zoomSnap: 0,
      zoomDelta: MAP_BUTTON_ZOOM_STEP,
      wheelPxPerZoomLevel: 72,
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

    addBaseMapLayer(map);

    airportLayerRef.current = L.layerGroup().addTo(map);

    // Crear canvas overlay
    const canvas = document.createElement("canvas");
    canvas.style.position = "absolute";
    canvas.style.top = "0";
    canvas.style.left = "0";
    canvas.style.pointerEvents = "none";
    canvas.className = "flight-canvas";
    
    (map.getPane("activeFlights") ?? map.getContainer()).appendChild(canvas);
    canvasRef.current = canvas;

    // Función para redimensionar y redibujar canvas
    const updateCanvasSize = () => updateFlightCanvasViewport(map, canvas, canvasOriginRef);

    updateCanvasSize();
    map.on("resize", updateCanvasSize);
    map.on("zoom zoomend", () => setZoom(Number(map.getZoom().toFixed(2))));

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

    const visibleCodes = new Set(visibleAirports.map((airport) => airport.code));

    for (const [code, item] of airportMarkersRef.current) {
      if (!visibleCodes.has(code)) {
        airportLayerRef.current.removeLayer(item.marker);
        airportMarkersRef.current.delete(code);
      }
    }

    for (const airport of visibleAirports) {
      const existing = airportMarkersRef.current.get(airport.code);
      if (existing) {
        existing.airport = airport;
        existing.marker.setLatLng([airport.latitude, airport.longitude]);
        continue;
      }

      const load = airportLoadsRef.current[airport.code] || 0;
      const marker = L.marker([airport.latitude, airport.longitude], {
        icon: createAirportIcon(airport, load, false),
        pane: "airports",
        interactive: true,
        keyboard: false,
        zIndexOffset: 1600,
      })
        .on("click", (event) => {
          L.DomEvent.stopPropagation(event);
          const currentAirport = airportMarkersRef.current.get(airport.code)?.airport ?? airport;
          const currentLoad = airportLoadsRef.current[currentAirport.code] || 0;
          const currentPeak = Math.max(
            currentLoad,
            airportPeakLoadsRef.current[currentAirport.code] ?? currentAirport.peakLoad ?? 0
          );
          onSelectAirportRef.current(currentAirport.code);
          setMapInfo(createAirportInfo(currentAirport, currentLoad, currentPeak));
        })
        .addTo(airportLayerRef.current);

      airportMarkersRef.current.set(airport.code, { airport, marker });
    }
  }, [visibleAirports]);

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
  // Filter flights according to color filter
  const filteredActiveFlights = useMemo(() => {
    if (flightColorFilter === "Todos") return activeFlights;
    return activeFlights.filter((flight) => capacityStatus(flight.utilization) === flightColorFilter);
  }, [activeFlights, flightColorFilter]);
  const drawFlights = useCallback((force = false) => {
    if (!canvasRef.current || !mapRef.current) return;

    updateFlightCanvasViewport(mapRef.current, canvasRef.current, canvasOriginRef);
    const canvasOrigin = canvasOriginRef.current;
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
      drawShipmentRoute(ctx, mapRef.current, selectedShipmentRoute, airportByCode, canvasOrigin);
    } else if (selectedFlight && !activeFlights.some((flight) => flight.id === selectedFlight.id)) {
      drawStaticFlightRoute(ctx, mapRef.current, selectedFlight, airportByCode, canvasOrigin);
    }

  for (const flight of filteredActiveFlights) {
    const origin = airportByCode[flight.origin];
    const destination = airportByCode[flight.destination];
    if (!origin || !destination) continue;

    const { destPixel, planePixel, angle } =
      getRouteGeometry(mapRef.current, origin, destination, flight.progress, canvasOrigin);
    const isSelected = flight.id === selectedFlightId;
    const status = capacityStatus(flight.utilization);
    const flightColor = flight.assignedLoad <= 0 ? STATUS_COLOR.gray : STATUS_COLOR[status];

    ctx.strokeStyle = flightColor;
    ctx.lineWidth = isSelected ? SELECTED_FLIGHT_ROUTE_LINE_WIDTH : FLIGHT_ROUTE_LINE_WIDTH;
    ctx.globalAlpha = isSelected ? 0.92 : 0.5;
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
    filteredActiveFlights
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

      const currentLoad = airportLoadsRef.current[airport.code] || 0;
      const currentPeak = Math.max(currentLoad, airportPeakLoadsRef.current[airport.code] ?? airport.peakLoad ?? 0);
      map.flyTo([airport.latitude, airport.longitude], Math.max(map.getZoom(), FOCUS_ZOOM), {
        animate: true,
        duration: 0.75,
      });
      setMapInfo(createAirportInfo(airport, currentLoad, currentPeak));
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
  }, [airportByCode, data, displayGmtOffset, focusTarget, selectedFlight, selectedShipment, selectedShipmentRoute]);

  useEffect(() => {
    const resizeAndRedraw = () => {
      window.requestAnimationFrame(() => {
        if (!mapRef.current || !canvasRef.current) return;
        mapRef.current.invalidateSize({ pan: false });
        updateFlightCanvasViewport(mapRef.current, canvasRef.current, canvasOriginRef);
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

      if (target?.closest(".airport-marker, .leaflet-control, .map-info-card, .map-zoom-control")) return;

      // Buscar avión cerca del click (hitarea de 15px)
      const filteredActiveFlights = activeFlights.filter((flight) => {
        const perc = flight.utilization;
        if (flightColorFilter === "green") return perc > 0 && perc < 0.5;
        if (flightColorFilter === "yellow") return perc >= 0.5 && perc < 0.8;
        if (flightColorFilter === "red") return perc >= 0.8;
        if (flightColorFilter === "gray") return perc <= 0.001;
        return true;
      });

      for (const flight of filteredActiveFlights) {
        const origin = airportByCode[flight.origin];
        const destination = airportByCode[flight.destination];
        if (!origin || !destination) continue;

        const { planePixel } = getRouteGeometry(
          map,
          origin,
          destination,
          flight.progress,
          canvasOriginRef.current
        );

        const dist = Math.hypot(x - planePixel.x, y - planePixel.y);
        if (dist < 12) {
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
  }, [activeFlights, airportByCode, data, displayGmtOffset, flightColorFilter, onClearSelection, onSelectFlight]);

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

    window.setTimeout(() => {
      fitDefaultAirportBounds(false);
      didFitBoundsRef.current = true;
    }, 0);
  }, [data, visibleAirports, fitDefaultAirportBounds]);

  const setMapZoom = useCallback((nextZoom: number) => {
    const map = mapRef.current;
    if (!map) return;

    const clamped = Math.min(MAP_MAX_ZOOM, Math.max(MAP_MIN_ZOOM, nextZoom));
    map.setZoom(clamped, { animate: false });
    setZoom(Number(clamped.toFixed(2)));
  }, []);

  useEffect(() => {
    if (resetViewToken === undefined || resetViewToken === lastResetViewTokenRef.current) return;

    lastResetViewTokenRef.current = resetViewToken;
    setMapInfo(null);
    fitDefaultAirportBounds(true);
  }, [fitDefaultAirportBounds, resetViewToken]);

  return (
    <div className="map-stage">
      <div className="map-header">
        <span className="badge">{data ? `${visibleAirports.length}/${data.airports.length} aeropuertos` : "Mapa operativo"}</span>
        <span className="badge">{data ? `${activeFlights.length} vuelos en aire` : "Simulación"}</span>
      </div>
      {mapInfo && <MapInfoCard info={mapInfo} onClose={handleCloseMapInfo} />}
      <div
        ref={mapElement}
        className="leaflet-map"
        role="img"
        aria-label="Mapa mundial con aeropuertos y vuelos activos"
      />
      <MapZoomControl zoom={zoom} onZoomChange={setMapZoom} onReset={() => fitDefaultAirportBounds(true)} />
      {children}
    </div>
  );
}

function addBaseMapLayer(map: L.Map) {
  const spanishLayer = L.tileLayer(SPANISH_MAP_TILES_URL, {
    maxZoom: 8,
    minZoom: 2,
    attribution: SPANISH_MAP_TILES_ATTRIBUTION,
  });

  spanishLayer.once("tileerror", () => {
    if (!map.hasLayer(spanishLayer)) return;
    map.removeLayer(spanishLayer);
    createDefaultMapLayer().addTo(map);
  });

  spanishLayer.addTo(map);
}

function createDefaultMapLayer() {
  return L.tileLayer(DEFAULT_MAP_TILES_URL, {
    maxZoom: 8,
    minZoom: 2,
    attribution: DEFAULT_MAP_TILES_ATTRIBUTION,
  });
}

function isAirportActive(airport: Airport) {
  if (typeof airport.active === "boolean") return airport.active;
  return (airport.operationalStatus ?? "ACTIVE").toUpperCase() !== "INACTIVE";
}

function MapInfoCard({ info, onClose }: { info: MapInfo; onClose: () => void }) {
  return (
    <aside className={`map-info-card ${info.type}`}>
      <button
        className="map-info-close"
        onClick={(event) => {
          event.stopPropagation();
          onClose();
        }}
        aria-label="Cerrar información"
      >
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

function MapZoomControl({
  zoom,
  onZoomChange,
  onReset,
}: {
  zoom: number;
  onZoomChange: (zoom: number) => void;
  onReset: () => void;
}) {
  return (
    <div className="map-zoom-control" aria-label="Control de zoom del mapa">
      <button
        type="button"
        className="map-zoom-button"
        onClick={() => onZoomChange(zoom - MAP_BUTTON_ZOOM_STEP)}
        aria-label="Alejar mapa"
      >
        -
      </button>
      <input
        className="map-zoom-slider"
        type="range"
        min={MAP_MIN_ZOOM}
        max={MAP_MAX_ZOOM}
        step={MAP_ZOOM_STEP}
        value={zoom}
        onChange={(event) => onZoomChange(Number(event.target.value))}
        aria-label="Ajustar zoom del mapa"
      />
      <button
        type="button"
        className="map-zoom-button"
        onClick={() => onZoomChange(zoom + MAP_BUTTON_ZOOM_STEP)}
        aria-label="Acercar mapa"
      >
        +
      </button>
      <button
        type="button"
        className="map-zoom-button map-zoom-reset"
        onClick={onReset}
        aria-label="Restaurar vista de aeropuertos"
        title="Restaurar vista de aeropuertos"
      >
        ↻
      </button>
    </div>
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

function formatMapPercent(value: number): string {
  return `${(value * 100).toLocaleString("es-PE", {
    minimumFractionDigits: 2,
    maximumFractionDigits: 2,
  })}%`;
}

function createAirportInfo(airport: Airport, load: number, peakLoad?: number): MapInfo {
  const effectivePeakLoad = Math.max(load, peakLoad ?? airport.peakLoad ?? 0);
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
      ["Uso", formatMapPercent(airport.maxCapacity ? load / airport.maxCapacity : 0)],
      [
        "Pico registrado",
        `${effectivePeakLoad}/${airport.maxCapacity} (${formatMapPercent(
          airport.maxCapacity ? effectivePeakLoad / airport.maxCapacity : 0
        )})`,
      ],
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
    title: `Envío ${shipment.id}`,
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
  airportByCode: Record<string, Airport>,
  canvasOrigin: L.Point
) {
  route.forEach((leg, index) => {
    const origin = airportByCode[leg.origin];
    const destination = airportByCode[leg.destination];
    if (!origin || !destination) return;

    drawRouteSegment(ctx, map, origin, destination, canvasOrigin, {
      color: "#f59e0b",
      width: 4.2,
      alpha: 0.86,
      dash: index % 2 === 0 ? [10, 6] : [4, 5],
      shadow: true,
    });
    drawRouteNode(ctx, map, origin, canvasOrigin, index === 0 ? "#22c55e" : "#f59e0b");
    drawRouteNode(ctx, map, destination, canvasOrigin, index === route.length - 1 ? "#ef4444" : "#f59e0b");
  });
}

function drawStaticFlightRoute(
  ctx: CanvasRenderingContext2D,
  map: L.Map,
  flight: Flight,
  airportByCode: Record<string, Airport>,
  canvasOrigin: L.Point
) {
  const origin = airportByCode[flight.origin];
  const destination = airportByCode[flight.destination];
  if (!origin || !destination) return;

  drawRouteSegment(ctx, map, origin, destination, canvasOrigin, {
    color: STATUS_COLOR[flight.status] ?? "#111827",
    width: 3.2,
    alpha: 0.82,
    dash: [8, 6],
    shadow: true,
  });
  drawRouteNode(ctx, map, origin, canvasOrigin, "#111827");
  drawRouteNode(ctx, map, destination, canvasOrigin, "#111827");
}

function drawRouteSegment(
  ctx: CanvasRenderingContext2D,
  map: L.Map,
  origin: Airport,
  destination: Airport,
  canvasOrigin: L.Point,
  options: { color: string; width: number; alpha: number; dash?: number[]; shadow?: boolean }
) {
  const { originPixel, destPixel } = getRouteGeometry(map, origin, destination, 0, canvasOrigin);
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
  canvasOrigin: L.Point,
  color: string
) {
  const point = map.latLngToLayerPoint([airport.latitude, airport.longitude]).subtract(canvasOrigin);
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

function updateFlightCanvasViewport(
  map: L.Map,
  canvas: HTMLCanvasElement,
  canvasOriginRef: { current: L.Point }
) {
  const size = map.getSize();
  const origin = map.containerPointToLayerPoint([0, 0]);
  if (canvas.width !== size.x) canvas.width = size.x;
  if (canvas.height !== size.y) canvas.height = size.y;
  canvasOriginRef.current = origin;
  L.DomUtil.setPosition(canvas, origin);
}

function getRouteGeometry(
  map: L.Map,
  origin: Airport,
  destination: Airport,
  progress: number,
  canvasOrigin: L.Point
) {
  const originPixel = map.latLngToLayerPoint([origin.latitude, origin.longitude]).subtract(canvasOrigin);
  const destPixel = map.latLngToLayerPoint([destination.latitude, destination.longitude]).subtract(canvasOrigin);
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
  ctx.scale(selected ? 0.5 : 0.38, selected ? 0.5 : 0.38);

  if (selected) {
    ctx.shadowColor = "rgba(15, 23, 42, 0.46)";
    ctx.shadowBlur = 14;
    ctx.shadowOffsetY = 3;
  }

  drawPlanePath(ctx);
  ctx.strokeStyle = "rgba(255, 255, 255, 0.95)";
  ctx.lineWidth = selected ? 5.2 : 4.2;
  ctx.lineJoin = "round";
  ctx.stroke();

  ctx.shadowBlur = 0;
  ctx.shadowOffsetY = 0;
  drawPlanePath(ctx);
  ctx.fillStyle = color;
  ctx.strokeStyle = selected ? "#111827" : "rgba(255, 255, 255, 0.9)";
  ctx.lineWidth = selected ? 1.8 : 1.25;
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
