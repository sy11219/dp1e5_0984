import { useEffect, useMemo, useRef } from "react";
import L from "leaflet";
import type { SimulationResponse } from "../../../types/SimulationResponse";

const STATUS_COLOR = {
  green: "#21a67a",
  yellow: "#d9a219",
  red: "#d84545",
};

type Props = {
  data: SimulationResponse | null;
  activeFlights: any[];
  airportLoads: Record<string, number>;
  onSelectAirport: (code: string) => void;
};

export default function MapStage({
  data,
  activeFlights,
  airportLoads,
  onSelectAirport,
}: Props) {
  const mapRef = useRef<L.Map | null>(null);
  const mapDivRef = useRef<HTMLDivElement | null>(null);
  const initializedRef = useRef(false); 

  const airportByCode = useMemo(() => {
    if (!data) return {};
    return Object.fromEntries(data.airports.map((a) => [a.code, a]));
  }, [data]);

  /* INIT MAP */
  useEffect(() => {
  if (!mapDivRef.current || mapRef.current || initializedRef.current) return;
  initializedRef.current = true;

  const map = L.map(mapDivRef.current).setView([10, -10], 2);

  L.tileLayer("https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png", {
    attribution: "&copy; OpenStreetMap",
  }).addTo(map);

  mapRef.current = map;

  return () => {
    map.remove();
    mapRef.current = null;
  };
}, []);

  /* AIRPORTS */
  useEffect(() => {
    if (!mapRef.current || !data) return;

    const map = mapRef.current;
    const layer = L.layerGroup().addTo(map);

    for (const airport of data.airports) {
      const load = airportLoads[airport.code] || 0;
      const ratio = load / airport.maxCapacity;

      const color =
        ratio < 0.7 ? "green" : ratio < 0.9 ? "yellow" : "red";

      const marker = L.circleMarker(
        [airport.latitude, airport.longitude],
        {
          radius: 6,
          color: STATUS_COLOR[color],
        }
      ).addTo(layer);

      marker.on("click", () => onSelectAirport(airport.code));
    }

    return () => {
      layer.clearLayers();
    };
  }, [data, airportLoads, onSelectAirport]);

  /* FLIGHTS */
  useEffect(() => {
    if (!mapRef.current || !data) return;

    const map = mapRef.current;
    const layer = L.layerGroup().addTo(map);

    for (const f of activeFlights) {
      const origin = airportByCode[f.origin];
      const dest = airportByCode[f.destination];

      if (!origin || !dest) continue;

      const lat =
        origin.latitude +
        (dest.latitude - origin.latitude) * f.progress;

      const lng =
        origin.longitude +
        (dest.longitude - origin.longitude) * f.progress;

      L.circleMarker([lat, lng], {
        radius: 4,
        color: "#fff",
      }).addTo(layer);
    }

    return () => {
      layer.clearLayers();
    };
  }, [activeFlights, airportByCode]);

  return (
    <div
      ref={mapDivRef}
      style={{ width: "100%", height: "100%" }}
    />
  );
}