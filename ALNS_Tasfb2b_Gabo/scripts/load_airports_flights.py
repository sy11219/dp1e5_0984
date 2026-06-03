#!/usr/bin/env python3
"""
Carga aeropuertos.txt y planes_vuelo.txt en PostgreSQL/RDS.

Uso recomendado:
  pip install "psycopg[binary]"

  python scripts/load_airports_flights.py \
    --db-url "postgresql://postgres:TU_PASSWORD@HOST_RDS:5432/tasf_b2b?sslmode=require" \
    --create-schema \
    --start-date 2026-01-01 \
    --days 5

Por defecto lee:
  data/aeropuertos.txt
  data/planes_vuelo.txt
"""

from __future__ import annotations

import argparse
import re
import uuid
from dataclasses import dataclass
from datetime import date, datetime, time, timedelta, timezone
from pathlib import Path
from typing import Any


AIRPORT_LINE_RE = re.compile(r"^\s*\d+\s+([A-Z]{4})\s+.*$")
LAT_RE = re.compile(r"Latitude:\s*(\d+)[°º]\s*(\d+)'\s*([\d.]+)\"\s*([NS])")
LON_RE = re.compile(r"Longitude:\s*(\d+)[°º]\s*(\d+)'\s*([\d.]+)\"\s*([EW])")
FLIGHT_RE = re.compile(r"^([A-Z]{4})-([A-Z]{4})-(\d{2}):(\d{2})-(\d{2}):(\d{2})-(\d{4})\s*$")


@dataclass(frozen=True)
class Airport:
    code: str
    city: str
    country: str
    continent: str
    gmt_offset: int
    capacity: int
    latitude: float
    longitude: float


def parse_args() -> argparse.Namespace:
    base_dir = Path(__file__).resolve().parents[1]
    parser = argparse.ArgumentParser(description="Carga aeropuertos y planes de vuelo en PostgreSQL.")
    parser.add_argument("--db-url", required=True, help="URL PostgreSQL. Ej: postgresql://user:pass@host:5432/db?sslmode=require")
    parser.add_argument("--airports", default=str(base_dir / "data" / "aeropuertos.txt"))
    parser.add_argument("--flights", default=str(base_dir / "data" / "planes_vuelo.txt"))
    parser.add_argument("--start-date", default="2026-01-01", help="Fecha base para materializar vuelos recurrentes.")
    parser.add_argument("--days", type=int, default=1, help="Cantidad de dias a generar para planes_vuelo.txt.")
    parser.add_argument("--create-schema", action="store_true", help="Crea airports y flight_plans si no existen.")
    return parser.parse_args()


def parse_dms(pattern: re.Pattern[str], line: str, negative_letter: str) -> float:
    match = pattern.search(line)
    if not match:
        return 0.0
    degrees = float(match.group(1))
    minutes = float(match.group(2))
    seconds = float(match.group(3))
    value = degrees + minutes / 60.0 + seconds / 3600.0
    return -value if match.group(4).upper() == negative_letter else value


def parse_airports(path: Path) -> list[Airport]:
    airports: list[Airport] = []
    current_continent = "Desconocido"

    with path.open("r", encoding="utf-16") as file:
        for raw_line in file:
            line = raw_line.strip()
            if not line or line.startswith("*") or line.startswith("PDDS"):
                continue

            upper_line = line.upper()
            if "AMERICA" in upper_line and not AIRPORT_LINE_RE.match(line):
                current_continent = "America"
                continue
            if "EUROPA" in upper_line and not AIRPORT_LINE_RE.match(line):
                current_continent = "Europa"
                continue
            if "ASIA" in upper_line and not AIRPORT_LINE_RE.match(line):
                current_continent = "Asia"
                continue

            if not AIRPORT_LINE_RE.match(line):
                continue

            lat_index = raw_line.find("Latitude:")
            if lat_index < 0:
                continue

            before_lat = raw_line[:lat_index].strip()
            parts = re.split(r"\s{2,}", before_lat)
            if len(parts) < 7:
                continue

            try:
                code = parts[1].strip()
                city = parts[2].strip()
                country = parts[3].strip()
                gmt_offset = int(parts[-2].strip())
                capacity = int(parts[-1].strip())
                latitude = parse_dms(LAT_RE, raw_line, "S")
                longitude = parse_dms(LON_RE, raw_line, "W")
            except (ValueError, IndexError):
                continue

            airports.append(
                Airport(
                    code=code,
                    city=city,
                    country=country,
                    continent=current_continent,
                    gmt_offset=gmt_offset,
                    capacity=capacity,
                    latitude=latitude,
                    longitude=longitude,
                )
            )

    return airports


def create_schema(conn: Any) -> None:
    with conn.cursor() as cur:
        cur.execute(
            """
            CREATE TABLE IF NOT EXISTS airports (
              id UUID PRIMARY KEY,
              code VARCHAR(10) UNIQUE NOT NULL,
              name VARCHAR(120) NOT NULL,
              city VARCHAR(120) NOT NULL,
              country VARCHAR(120) NOT NULL,
              continent VARCHAR(40) NOT NULL,
              latitude NUMERIC(9,6),
              longitude NUMERIC(9,6),
              timezone VARCHAR(64) NOT NULL,
              warehouse_capacity INT NOT NULL,
              status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
              created_at TIMESTAMPTZ DEFAULT now()
            )
            """
        )
        cur.execute(
            """
            CREATE TABLE IF NOT EXISTS flight_plans (
              id UUID PRIMARY KEY,
              flight_code VARCHAR(60) UNIQUE NOT NULL,
              origin_airport_id UUID NOT NULL REFERENCES airports(id),
              destination_airport_id UUID NOT NULL REFERENCES airports(id),
              departure_time_local TIMESTAMP NOT NULL,
              arrival_time_local TIMESTAMP NOT NULL,
              departure_time_utc TIMESTAMPTZ NOT NULL,
              arrival_time_utc TIMESTAMPTZ NOT NULL,
              capacity INT NOT NULL,
              status VARCHAR(20) NOT NULL DEFAULT 'SCHEDULED',
              created_at TIMESTAMPTZ DEFAULT now()
            )
            """
        )
        cur.execute(
            """
            CREATE INDEX IF NOT EXISTS idx_flights_origin_date
            ON flight_plans(origin_airport_id, departure_time_utc)
            """
        )
        cur.execute(
            """
            CREATE INDEX IF NOT EXISTS idx_flights_destination_date
            ON flight_plans(destination_airport_id, arrival_time_utc)
            """
        )


def timezone_label(gmt_offset: int) -> str:
    return f"UTC{gmt_offset:+03d}:00"


def upsert_airports(conn: Any, airports: list[Airport]) -> dict[str, uuid.UUID]:
    airport_ids: dict[str, uuid.UUID] = {}

    with conn.cursor() as cur:
        for airport in airports:
            airport_id = uuid.uuid4()
            cur.execute(
                """
                INSERT INTO airports (
                  id, code, name, city, country, continent, latitude, longitude,
                  timezone, warehouse_capacity, status
                )
                VALUES (%s, %s, %s, %s, %s, %s, %s, %s, %s, %s, 'ACTIVE')
                ON CONFLICT (code) DO UPDATE SET
                  name = EXCLUDED.name,
                  city = EXCLUDED.city,
                  country = EXCLUDED.country,
                  continent = EXCLUDED.continent,
                  latitude = EXCLUDED.latitude,
                  longitude = EXCLUDED.longitude,
                  timezone = EXCLUDED.timezone,
                  warehouse_capacity = EXCLUDED.warehouse_capacity,
                  status = EXCLUDED.status
                RETURNING id
                """,
                (
                    airport_id,
                    airport.code,
                    airport.city,
                    airport.city,
                    airport.country,
                    airport.continent,
                    airport.latitude,
                    airport.longitude,
                    timezone_label(airport.gmt_offset),
                    airport.capacity,
                ),
            )
            airport_ids[airport.code] = cur.fetchone()[0]

    return airport_ids


def to_utc(local_dt: datetime, gmt_offset: int) -> datetime:
    local_zone = timezone(timedelta(hours=gmt_offset))
    return local_dt.replace(tzinfo=local_zone).astimezone(timezone.utc)


def parse_flight_rows(
    flights_path: Path,
    start_date: date,
    days: int,
    airports: dict[str, Airport],
    airport_ids: dict[str, uuid.UUID],
) -> list[tuple]:
    rows: list[tuple] = []
    base_flight_index = 0

    with flights_path.open("r", encoding="utf-8") as file:
        for raw_line in file:
            line = raw_line.strip()
            if not line or line.startswith("#") or line.startswith("//"):
                continue

            match = FLIGHT_RE.match(line)
            if not match:
                print(f"Vuelo ignorado por formato invalido: {line}")
                continue

            base_flight_index += 1
            origin_code = match.group(1)
            destination_code = match.group(2)
            dep_hour = int(match.group(3))
            dep_minute = int(match.group(4))
            arr_hour = int(match.group(5))
            arr_minute = int(match.group(6))
            capacity = int(match.group(7))

            if origin_code not in airports or destination_code not in airports:
                raise ValueError(f"Vuelo referencia aeropuerto no cargado: {line}")

            origin = airports[origin_code]
            destination = airports[destination_code]

            for day_offset in range(days):
                flight_date = start_date + timedelta(days=day_offset)
                departure_local = datetime.combine(flight_date, time(dep_hour, dep_minute))
                arrival_local = datetime.combine(flight_date, time(arr_hour, arr_minute))

                departure_utc = to_utc(departure_local, origin.gmt_offset)
                arrival_utc = to_utc(arrival_local, destination.gmt_offset)
                while arrival_utc <= departure_utc:
                    arrival_local += timedelta(days=1)
                    arrival_utc = to_utc(arrival_local, destination.gmt_offset)

                flight_code = (
                    f"{origin_code}-{destination_code}-"
                    f"{flight_date:%Y%m%d}-{dep_hour:02d}{dep_minute:02d}-{base_flight_index:04d}"
                )

                rows.append(
                    (
                        uuid.uuid4(),
                        flight_code,
                        airport_ids[origin_code],
                        airport_ids[destination_code],
                        departure_local,
                        arrival_local,
                        departure_utc,
                        arrival_utc,
                        capacity,
                    )
                )

    return rows


def upsert_flights(conn: Any, rows: list[tuple]) -> None:
    with conn.cursor() as cur:
        for row in rows:
            cur.execute(
                """
                INSERT INTO flight_plans (
                  id, flight_code, origin_airport_id, destination_airport_id,
                  departure_time_local, arrival_time_local,
                  departure_time_utc, arrival_time_utc,
                  capacity, status
                )
                VALUES (%s, %s, %s, %s, %s, %s, %s, %s, %s, 'SCHEDULED')
                ON CONFLICT (flight_code) DO UPDATE SET
                  origin_airport_id = EXCLUDED.origin_airport_id,
                  destination_airport_id = EXCLUDED.destination_airport_id,
                  departure_time_local = EXCLUDED.departure_time_local,
                  arrival_time_local = EXCLUDED.arrival_time_local,
                  departure_time_utc = EXCLUDED.departure_time_utc,
                  arrival_time_utc = EXCLUDED.arrival_time_utc,
                  capacity = EXCLUDED.capacity,
                  status = EXCLUDED.status
                """,
                row,
            )


def main() -> None:
    import psycopg

    args = parse_args()
    airports_path = Path(args.airports)
    flights_path = Path(args.flights)
    start_date = date.fromisoformat(args.start_date)

    if args.days < 1:
        raise ValueError("--days debe ser mayor o igual a 1")

    airports = parse_airports(airports_path)
    airports_by_code = {airport.code: airport for airport in airports}

    print(f"Aeropuertos parseados: {len(airports)}")

    with psycopg.connect(args.db_url) as conn:
        if args.create_schema:
            create_schema(conn)

        airport_ids = upsert_airports(conn, airports)
        flight_rows = parse_flight_rows(
            flights_path=flights_path,
            start_date=start_date,
            days=args.days,
            airports=airports_by_code,
            airport_ids=airport_ids,
        )
        upsert_flights(conn, flight_rows)
        conn.commit()

    print(f"Planes de vuelo cargados: {len(flight_rows)}")
    print("Carga completada.")


if __name__ == "__main__":
    main()
