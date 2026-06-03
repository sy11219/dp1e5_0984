#!/usr/bin/env python3
"""
Carga todos los archivos data/envios/_envios_XXXX_.txt en la tabla shipments.

Uso recomendado:
  pip install "psycopg[binary]"

  python scripts/load_shipments.py \
    --db-url "postgresql://postgres:TU_PASSWORD@HOST_RDS:5432/tasf_b2b?sslmode=require" \
    --create-schema

Requiere que la tabla airports ya este cargada, porque usa:
  - airports.id
  - airports.code
  - airports.continent
  - airports.timezone
"""

from __future__ import annotations

import argparse
import re
import uuid
from dataclasses import dataclass
from datetime import datetime, timedelta, timezone
from pathlib import Path
from collections.abc import Iterator
from typing import Any


FILE_RE = re.compile(r"_envio[s]?_([A-Z]{4})_\.txt$", re.IGNORECASE)
LINE_RE = re.compile(r"^(\d+)-(\d{8})-(\d{2})-(\d{2})-([A-Z]{4})-(\d{3})-(\d{7})\s*$")
TZ_RE = re.compile(r"^UTC([+-])(\d{2}):(\d{2})$")


@dataclass(frozen=True)
class AirportRef:
    id: uuid.UUID
    code: str
    continent: str
    timezone_label: str
    offset: timezone


@dataclass(frozen=True)
class ShipmentRow:
    id: uuid.UUID
    shipment_code: str
    raw_shipment_id: str
    client_id: str
    origin_airport_id: uuid.UUID
    destination_airport_id: uuid.UUID
    baggage_count: int
    registered_at: datetime
    max_delivery_at: datetime
    status: str
    raw_date: str
    raw_hour: str
    raw_minute: str


def parse_args() -> argparse.Namespace:
    base_dir = Path(__file__).resolve().parents[1]
    parser = argparse.ArgumentParser(description="Carga archivos de envios en PostgreSQL.")
    parser.add_argument("--db-url", required=True, help="URL PostgreSQL. Ej: postgresql://user:pass@host:5432/db?sslmode=require")
    parser.add_argument("--shipments-dir", default=str(base_dir / "data" / "envios"))
    parser.add_argument("--create-schema", action="store_true", help="Crea/ajusta la tabla shipments si no existe.")
    return parser.parse_args()


def parse_timezone(label: str) -> timezone:
    match = TZ_RE.match(label)
    if not match:
        raise ValueError(f"Formato timezone no soportado en airports.timezone: {label}")

    sign = 1 if match.group(1) == "+" else -1
    hours = int(match.group(2))
    minutes = int(match.group(3))
    return timezone(sign * timedelta(hours=hours, minutes=minutes))


def parse_local_datetime(raw_date: str, raw_hour: str, raw_minute: str, origin_tz: timezone) -> datetime:
    local_dt = datetime(
        year=int(raw_date[0:4]),
        month=int(raw_date[4:6]),
        day=int(raw_date[6:8]),
        hour=int(raw_hour),
        minute=int(raw_minute),
        tzinfo=origin_tz,
    )
    return local_dt.astimezone(timezone.utc)


def create_schema(conn: Any) -> None:
    with conn.cursor() as cur:
        cur.execute(
            """
            CREATE TABLE IF NOT EXISTS shipments (
              id UUID PRIMARY KEY,
              shipment_code VARCHAR(80) UNIQUE NOT NULL,
              origin_airport_id UUID NOT NULL REFERENCES airports(id),
              destination_airport_id UUID NOT NULL REFERENCES airports(id),
              baggage_count INT NOT NULL CHECK (baggage_count > 0),
              registered_at TIMESTAMPTZ NOT NULL,
              max_delivery_at TIMESTAMPTZ NOT NULL,
              status VARCHAR(30) NOT NULL DEFAULT 'REGISTERED',
              created_at TIMESTAMPTZ DEFAULT now()
            )
            """
        )
        cur.execute("CREATE INDEX IF NOT EXISTS idx_shipments_code ON shipments(shipment_code)")
        cur.execute("CREATE INDEX IF NOT EXISTS idx_shipments_status ON shipments(status)")
        cur.execute("CREATE INDEX IF NOT EXISTS idx_shipments_origin ON shipments(origin_airport_id)")
        cur.execute("CREATE INDEX IF NOT EXISTS idx_shipments_destination ON shipments(destination_airport_id)")
        cur.execute("CREATE INDEX IF NOT EXISTS idx_shipments_registered_at ON shipments(registered_at)")


def load_airports(conn: Any) -> dict[str, AirportRef]:
    airports: dict[str, AirportRef] = {}
    with conn.cursor() as cur:
        cur.execute("SELECT id, code, continent, timezone FROM airports")
        for airport_id, code, continent, timezone_label in cur.fetchall():
            airports[code] = AirportRef(
                id=airport_id,
                code=code,
                continent=continent,
                timezone_label=timezone_label,
                offset=parse_timezone(timezone_label),
            )
    return airports


def deadline_days(origin: AirportRef, destination: AirportRef) -> int:
    return 1 if origin.continent.lower() == destination.continent.lower() else 2


def iter_shipments(shipments_dir: Path, airports: dict[str, AirportRef]) -> Iterator[ShipmentRow]:
    for path in sorted(shipments_dir.glob("*.txt")):
        file_match = FILE_RE.match(path.name)
        if not file_match:
            continue

        origin_code = file_match.group(1).upper()
        origin = airports.get(origin_code)
        if origin is None:
            raise ValueError(f"El origen {origin_code} de {path.name} no existe en airports")

        file_count = 0
        with path.open("r", encoding="utf-8") as file:
            for line_number, raw_line in enumerate(file, start=1):
                line = raw_line.strip()
                if not line or line.startswith("#") or line.startswith("//"):
                    continue

                line_match = LINE_RE.match(line)
                if not line_match:
                    print(f"Linea ignorada por formato invalido {path.name}:{line_number}: {line}")
                    continue

                raw_shipment_id = line_match.group(1)
                raw_date = line_match.group(2)
                raw_hour = line_match.group(3)
                raw_minute = line_match.group(4)
                destination_code = line_match.group(5)
                baggage_count = int(line_match.group(6))
                client_id = line_match.group(7)

                destination = airports.get(destination_code)
                if destination is None:
                    raise ValueError(
                        f"El destino {destination_code} en {path.name}:{line_number} no existe en airports"
                    )

                registered_at = parse_local_datetime(raw_date, raw_hour, raw_minute, origin.offset)
                max_delivery_at = registered_at + timedelta(days=deadline_days(origin, destination))
                shipment_code = f"{origin_code}-{raw_shipment_id}"

                yield ShipmentRow(
                    id=uuid.uuid4(),
                    shipment_code=shipment_code,
                    raw_shipment_id=raw_shipment_id,
                    client_id=client_id,
                    origin_airport_id=origin.id,
                    destination_airport_id=destination.id,
                    baggage_count=baggage_count,
                    registered_at=registered_at,
                    max_delivery_at=max_delivery_at,
                    status="REGISTERED",
                    raw_date=raw_date,
                    raw_hour=raw_hour,
                    raw_minute=raw_minute,
                )
                file_count += 1

        print(f"{path.name}: {file_count} envios parseados")

def shipment_params(row: ShipmentRow) -> tuple:
    return (
        row.id,
        row.shipment_code,
        row.origin_airport_id,
        row.destination_airport_id,
        row.baggage_count,
        row.registered_at,
        row.max_delivery_at,
        row.status,
    )


def upsert_shipments(conn: Any, rows: Iterator[ShipmentRow], batch_size: int = 5000) -> int:
    sql = """
        INSERT INTO shipments (
          id, shipment_code, origin_airport_id, destination_airport_id,
          baggage_count, registered_at, max_delivery_at,
          status
        )
        VALUES (%s, %s, %s, %s, %s, %s, %s, %s)
        ON CONFLICT (shipment_code) DO UPDATE SET
          origin_airport_id = EXCLUDED.origin_airport_id,
          destination_airport_id = EXCLUDED.destination_airport_id,
          baggage_count = EXCLUDED.baggage_count,
          registered_at = EXCLUDED.registered_at,
          max_delivery_at = EXCLUDED.max_delivery_at,
          status = EXCLUDED.status
        """

    total = 0
    batch: list[tuple] = []

    with conn.cursor() as cur:
        for row in rows:
            batch.append(shipment_params(row))
            if len(batch) >= batch_size:
                cur.executemany(sql, batch)
                total += len(batch)
                batch.clear()
                conn.commit()
                print(f"Envios cargados hasta ahora: {total}")

        if batch:
            cur.executemany(sql, batch)
            total += len(batch)
            conn.commit()
            print(f"Envios cargados hasta ahora: {total}")

    return total


def main() -> None:
    import psycopg

    args = parse_args()
    shipments_dir = Path(args.shipments_dir)

    with psycopg.connect(args.db_url) as conn:
        if args.create_schema:
            create_schema(conn)

        airports = load_airports(conn)
        if not airports:
            raise RuntimeError("No hay aeropuertos cargados. Ejecuta primero load_airports_flights.py.")

        total = upsert_shipments(conn, iter_shipments(shipments_dir, airports))

    print(f"Envios cargados: {total}")
    print("Carga completada.")


if __name__ == "__main__":
    main()
