"""
AWS Lambda: carga envios desde S3 hacia PostgreSQL/RDS para una ventana de simulacion.

Evento esperado:
{
  "fecha_inicio": "2026-01-02",
  "dias": 5
}

Tambien acepta fecha_inicio en formato "20260102".

Variables de entorno:
  S3_BUCKET       Bucket donde estan los txt.
  S3_PREFIX       Prefijo/carpeta dentro del bucket. Ej: data/envios/
  DB_URL          postgresql://user:password@host:5432/tasf_b2b?sslmode=require
  BATCH_SIZE      Opcional. Default: 5000.
  RETURN_LIMIT    Opcional. Default: 5000. Evita respuestas gigantes de Lambda.

Dependencias de despliegue:
  boto3 viene incluido normalmente en Lambda.
  psycopg[binary] debe empaquetarse en el zip o agregarse como Lambda Layer.
"""

from __future__ import annotations

import json
import os
import re
import uuid
from dataclasses import dataclass
from datetime import date, datetime, timedelta, timezone
from typing import Any
from urllib.parse import unquote_plus

import boto3
import psycopg


FILE_RE = re.compile(r"_envio[s]?_([A-Z]{4})_\.txt$", re.IGNORECASE)
LINE_RE = re.compile(r"^(\d+)-(\d{8})-(\d{2})-(\d{2})-([A-Z]{4})-(\d{3})-(\d{7})\s*$")
TZ_RE = re.compile(r"^UTC([+-])(\d{2}):(\d{2})$")

s3 = boto3.client("s3")


@dataclass(frozen=True)
class AirportRef:
    id: uuid.UUID
    code: str
    continent: str
    offset: timezone


@dataclass(frozen=True)
class ShipmentRow:
    id: uuid.UUID
    shipment_code: str
    origin_airport_id: uuid.UUID
    destination_airport_id: uuid.UUID
    baggage_count: int
    registered_at: datetime
    max_delivery_at: datetime
    status: str
    origin_code: str
    destination_code: str
    client_id: str


def lambda_handler(event: dict[str, Any], context: Any) -> dict[str, Any]:
    fecha_inicio = parse_date(required(event, "fecha_inicio"))
    dias = int(required(event, "dias"))
    if dias < 1:
        raise ValueError("dias debe ser mayor o igual a 1")

    bucket = required_env("S3_BUCKET")
    prefix = os.getenv("S3_PREFIX", "data/envios/").strip()
    db_url = required_env("DB_URL")
    batch_size = int(os.getenv("BATCH_SIZE", "5000"))
    return_limit = int(os.getenv("RETURN_LIMIT", "5000"))

    fecha_fin = fecha_inicio + timedelta(days=dias)

    with psycopg.connect(db_url) as conn:
        airports = load_airports(conn)
        if not airports:
            raise RuntimeError("No hay aeropuertos cargados en la tabla airports")

        total_inserted = 0
        returned_shipments: list[dict[str, Any]] = []
        batch: list[ShipmentRow] = []

        for key in list_s3_txt_keys(bucket, prefix):
            origin_code = origin_from_key(key)
            if origin_code is None:
                continue
            if origin_code not in airports:
                raise ValueError(f"El origen {origin_code} de {key} no existe en airports")

            for row in iter_s3_shipments(bucket, key, origin_code, airports, fecha_inicio, fecha_fin):
                batch.append(row)
                if len(returned_shipments) < return_limit:
                    returned_shipments.append(to_response_item(row))

                if len(batch) >= batch_size:
                    total_inserted += upsert_shipments(conn, batch)
                    batch.clear()

        if batch:
            total_inserted += upsert_shipments(conn, batch)

        conn.commit()

    return {
        "fecha_inicio": fecha_inicio.isoformat(),
        "dias": dias,
        "fecha_fin_exclusiva": fecha_fin.isoformat(),
        "insertados": total_inserted,
        "devueltos": len(returned_shipments),
        "return_limit": return_limit,
        "truncated": total_inserted > len(returned_shipments),
        "shipments": returned_shipments,
    }


def required(event: dict[str, Any], key: str) -> Any:
    value = event.get(key)
    if value is None or value == "":
        raise ValueError(f"Falta parametro requerido: {key}")
    return value


def required_env(key: str) -> str:
    value = os.getenv(key)
    if not value:
        raise RuntimeError(f"Falta variable de entorno: {key}")
    return value


def parse_date(value: str) -> date:
    value = str(value).strip()
    if re.fullmatch(r"\d{8}", value):
        return date(int(value[0:4]), int(value[4:6]), int(value[6:8]))
    return date.fromisoformat(value)


def parse_timezone(label: str) -> timezone:
    match = TZ_RE.match(label)
    if not match:
        raise ValueError(f"Formato timezone no soportado en airports.timezone: {label}")
    sign = 1 if match.group(1) == "+" else -1
    hours = int(match.group(2))
    minutes = int(match.group(3))
    return timezone(sign * timedelta(hours=hours, minutes=minutes))


def load_airports(conn: Any) -> dict[str, AirportRef]:
    airports: dict[str, AirportRef] = {}
    with conn.cursor() as cur:
        cur.execute("SELECT id, code, continent, timezone FROM airports")
        for airport_id, code, continent, timezone_label in cur.fetchall():
            airports[code] = AirportRef(
                id=airport_id,
                code=code,
                continent=continent,
                offset=parse_timezone(timezone_label),
            )
    return airports


def list_s3_txt_keys(bucket: str, prefix: str) -> list[str]:
    keys: list[str] = []
    continuation_token = None

    while True:
        kwargs: dict[str, Any] = {"Bucket": bucket, "Prefix": prefix}
        if continuation_token:
            kwargs["ContinuationToken"] = continuation_token

        response = s3.list_objects_v2(**kwargs)
        for item in response.get("Contents", []):
            key = item["Key"]
            if key.lower().endswith(".txt"):
                keys.append(key)

        if not response.get("IsTruncated"):
            break
        continuation_token = response.get("NextContinuationToken")

    return sorted(keys)


def origin_from_key(key: str) -> str | None:
    filename = unquote_plus(key.rsplit("/", 1)[-1])
    match = FILE_RE.match(filename)
    return match.group(1).upper() if match else None


def iter_s3_shipments(
    bucket: str,
    key: str,
    origin_code: str,
    airports: dict[str, AirportRef],
    fecha_inicio: date,
    fecha_fin: date,
):
    origin = airports[origin_code]
    response = s3.get_object(Bucket=bucket, Key=key)

    for line_number, raw_bytes in enumerate(response["Body"].iter_lines(), start=1):
        if not raw_bytes:
            continue

        line = raw_bytes.decode("utf-8").strip()
        if not line or line.startswith("#") or line.startswith("//"):
            continue

        match = LINE_RE.match(line)
        if not match:
            print(f"Linea ignorada por formato invalido {key}:{line_number}: {line}")
            continue

        raw_shipment_id = match.group(1)
        raw_date = match.group(2)
        raw_hour = match.group(3)
        raw_minute = match.group(4)
        destination_code = match.group(5)
        baggage_count = int(match.group(6))
        client_id = match.group(7)
        shipment_date = parse_date(raw_date)

        if shipment_date < fecha_inicio or shipment_date >= fecha_fin:
            continue

        destination = airports.get(destination_code)
        if destination is None:
            raise ValueError(f"El destino {destination_code} en {key}:{line_number} no existe en airports")

        registered_at = local_datetime_to_utc(raw_date, raw_hour, raw_minute, origin.offset)
        max_delivery_at = registered_at + timedelta(days=deadline_days(origin, destination))

        yield ShipmentRow(
            id=uuid.uuid4(),
            shipment_code=f"{origin_code}-{raw_shipment_id}",
            origin_airport_id=origin.id,
            destination_airport_id=destination.id,
            baggage_count=baggage_count,
            registered_at=registered_at,
            max_delivery_at=max_delivery_at,
            status="REGISTERED",
            origin_code=origin_code,
            destination_code=destination_code,
            client_id=client_id,
        )


def local_datetime_to_utc(raw_date: str, raw_hour: str, raw_minute: str, origin_tz: timezone) -> datetime:
    local_dt = datetime(
        year=int(raw_date[0:4]),
        month=int(raw_date[4:6]),
        day=int(raw_date[6:8]),
        hour=int(raw_hour),
        minute=int(raw_minute),
        tzinfo=origin_tz,
    )
    return local_dt.astimezone(timezone.utc)


def deadline_days(origin: AirportRef, destination: AirportRef) -> int:
    return 1 if origin.continent.lower() == destination.continent.lower() else 2


def upsert_shipments(conn: Any, rows: list[ShipmentRow]) -> int:
    sql = """
        INSERT INTO shipments (
          id, shipment_code, origin_airport_id, destination_airport_id,
          baggage_count, registered_at, max_delivery_at, status
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
    params = [
        (
            row.id,
            row.shipment_code,
            row.origin_airport_id,
            row.destination_airport_id,
            row.baggage_count,
            row.registered_at,
            row.max_delivery_at,
            row.status,
        )
        for row in rows
    ]
    with conn.cursor() as cur:
        cur.executemany(sql, params)
    return len(rows)


def to_response_item(row: ShipmentRow) -> dict[str, Any]:
    return {
        "shipment_code": row.shipment_code,
        "origin_code": row.origin_code,
        "destination_code": row.destination_code,
        "baggage_count": row.baggage_count,
        "registered_at": row.registered_at.isoformat(),
        "max_delivery_at": row.max_delivery_at.isoformat(),
        "status": row.status,
        "client_id": row.client_id,
    }


if __name__ == "__main__":
    print(json.dumps(lambda_handler({"fecha_inicio": "2026-01-02", "dias": 1}, None), indent=2))
