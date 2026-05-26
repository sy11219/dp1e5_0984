import { useEffect, useMemo, useRef, useState } from "react";
import L from "leaflet";
import type { ActiveFlight, Airport, AirportLoads, MapInfoCard as MapInfo, SimulationData } from "../../../types";
import { STATUS_COLOR, MAP_CONFIG, PANE_Z_INDEX } from "../../../utils/constants";
import { bearingDegrees, capacityStatus } from "../../../utils/calculations";
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
  const routeLayerRef = useRef<L.LayerGroup | null>(null);
  const airportLayerRef = useRef<L.LayerGroup | null>(null);
  const planeLayerRef = useRef<L.LayerGroup | null>(null);
  const airportLoadsRef = useRef<AirportLoads>({});
  const airportMarkersRef = useRef(new Map<string, AirportMarkerItem>());

  const airports = useMemo(() => data?.airports || [], [data]);
  const airportByCode = useMemo(
    () => Object.fromEntries(airports.map((airport) => [airport.code, airport])),
    [airports]
  );

  useEffect(() => {
    airportLoadsRef.current = airportLoads;
  }, [airportLoads]);

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

    routeLayerRef.current = L.layerGroup().addTo(map);
    airportLayerRef.current = L.layerGroup().addTo(map);
    planeLayerRef.current = L.layerGroup().addTo(map);

    const timers = [0, 100, 350].map((delay) =>
      window.setTimeout(() => {
        mapRef.current?.invalidateSize();
        mapRef.current?.setView(MAP_CENTER, MAP_CONFIG.zoom, { animate: false });
      }, delay)
    );

    return () => {
      timers.forEach(window.clearTimeout);
      mapRef.current?.remove();
      mapRef.current = null;
    };
  }, []);

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

  useEffect(() => {
    if (!planeLayerRef.current) return;

    planeLayerRef.current.clearLayers();

    for (const flight of activeFlights) {
      const origin = airportByCode[flight.origin];
      const destination = airportByCode[flight.destination];
      if (!origin || !destination) continue;

      const latitude = origin.latitude + (destination.latitude - origin.latitude) * flight.progress;
      const longitude = origin.longitude + (destination.longitude - origin.longitude) * flight.progress;
      const angle = bearingDegrees(
        origin.latitude,
        origin.longitude,
        destination.latitude,
        destination.longitude
      );

      L.marker([latitude, longitude], {
        icon: L.divIcon({
          className: "flight-map-icon",
          html: planeSvg(STATUS_COLOR[flight.status], angle),
          iconSize: [26, 26],
          iconAnchor: [13, 13],
        }),
        pane: "activeFlights",
      })
        .on("click", () => setMapInfo(createFlightInfo(data, flight, origin, destination)))
        .addTo(planeLayerRef.current);
    }
  }, [activeFlights, airportByCode, data]);

  useEffect(() => {
    if (!data || !mapRef.current || !airports.length) return;

    const bounds = L.latLngBounds(
      airports.map((airport) => [airport.latitude, airport.longitude])
    );

    window.setTimeout(() => {
      mapRef.current?.invalidateSize();
      mapRef.current?.fitBounds(bounds.pad(0.16), { maxZoom: 3, animate: false });
    }, 0);
  }, [data, airports]);

  return (
    <div className="map-stage">
      <div className="map-header">
        <span className="badge">{data ? `${data.airports.length} aeropuertos` : "Mapa operativo"}</span>
        <span className="badge">{data ? `${activeFlights.length} vuelos en aire` : "ALNS"}</span>
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

function planeSvg(color: string, angle: number) {
  return `
    <svg class="plane-svg" viewBox="-24 -24 48 48" style="transform: rotate(${angle}deg)" aria-hidden="true">
      <path class="plane-halo" d="M0 -22 C5 -22 7 -15 7 -6 L23 6 L23 13 L5 7 L4 17 L9 22 L9 24 L0 20 L-9 24 L-9 22 L-4 17 L-5 7 L-23 13 L-23 6 L-7 -6 C-7 -15 -5 -22 0 -22 Z"></path>
      <path class="plane-body" fill="${color}" d="M0 -22 C5 -22 7 -15 7 -6 L23 6 L23 13 L5 7 L4 17 L9 22 L9 24 L0 20 L-9 24 L-9 22 L-4 17 L-5 7 L-23 13 L-23 6 L-7 -6 C-7 -15 -5 -22 0 -22 Z"></path>
    </svg>
  `;
}
