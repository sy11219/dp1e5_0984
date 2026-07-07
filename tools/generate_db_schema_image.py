from __future__ import annotations

from pathlib import Path
from PIL import Image, ImageDraw, ImageFont
import math


OUT = Path("diagrams/db_schema_tasfb2b.png")
W, H = 6200, 4300


def font(size: int, bold: bool = False):
    candidates = [
        "C:/Windows/Fonts/arialbd.ttf" if bold else "C:/Windows/Fonts/arial.ttf",
        "C:/Windows/Fonts/segoeuib.ttf" if bold else "C:/Windows/Fonts/segoeui.ttf",
    ]
    for candidate in candidates:
        try:
            return ImageFont.truetype(candidate, size)
        except OSError:
            pass
    return ImageFont.load_default()


TITLE = font(46, True)
SUBTITLE = font(24)
HEADER = font(24, True)
FIELD = font(19)
FIELD_BOLD = font(19, True)
TAG = font(16, True)
LEGEND = font(18)


TABLES = {
    "airports": {
        "pos": (90, 300),
        "color": "#2563eb",
        "fields": [
            ("PK", "id UUID"),
            ("UK", "code VARCHAR(10)"),
            ("", "name VARCHAR(120)"),
            ("", "city VARCHAR(120)"),
            ("", "country VARCHAR(120)"),
            ("", "continent VARCHAR(40)"),
            ("", "latitude NUMERIC(9,6)"),
            ("", "longitude NUMERIC(9,6)"),
            ("", "timezone VARCHAR(64)"),
            ("", "warehouse_capacity INT"),
            ("", "status VARCHAR(20)"),
            ("", "created_at TIMESTAMPTZ"),
        ],
    },
    "flight_plans": {
        "pos": (1540, 300),
        "color": "#0891b2",
        "fields": [
            ("PK", "id UUID"),
            ("UK", "flight_code VARCHAR(40)"),
            ("FK", "origin_airport_id -> airports.id"),
            ("FK", "destination_airport_id -> airports.id"),
            ("", "departure_time_local TIMESTAMP"),
            ("", "arrival_time_local TIMESTAMP"),
            ("", "departure_time_utc TIMESTAMPTZ"),
            ("", "arrival_time_utc TIMESTAMPTZ"),
            ("", "capacity INT"),
            ("", "status VARCHAR(20)"),
            ("", "created_at TIMESTAMPTZ"),
        ],
    },
    "shipments": {
        "pos": (90, 1120),
        "color": "#16a34a",
        "fields": [
            ("PK", "id UUID"),
            ("UK", "shipment_code VARCHAR(50)"),
            ("FK", "origin_airport_id -> airports.id"),
            ("FK", "destination_airport_id -> airports.id"),
            ("", "baggage_count INT"),
            ("", "registered_at TIMESTAMPTZ"),
            ("", "max_delivery_at TIMESTAMPTZ"),
            ("", "status VARCHAR(30)"),
            ("", "created_at TIMESTAMPTZ"),
        ],
    },
    "baggage_groups": {
        "pos": (90, 1780),
        "color": "#65a30d",
        "fields": [
            ("PK", "id UUID"),
            ("FK", "shipment_id -> shipments.id"),
            ("UK", "group_code VARCHAR(60)"),
            ("", "baggage_count INT"),
            ("FK", "current_airport_id -> airports.id"),
            ("FK", "current_flight_id -> flight_plans.id"),
            ("", "status VARCHAR(30)"),
            ("", "deadline_at TIMESTAMPTZ"),
            ("", "delivered_at TIMESTAMPTZ"),
            ("", "created_at TIMESTAMPTZ"),
        ],
    },
    "warehouse_movements": {
        "pos": (90, 2580),
        "color": "#7c3aed",
        "fields": [
            ("PK", "id UUID"),
            ("FK", "airport_id -> airports.id"),
            ("FK", "baggage_group_id -> baggage_groups.id"),
            ("FK", "flight_plan_id -> flight_plans.id"),
            ("", "movement_type VARCHAR(20)"),
            ("", "baggage_count INT"),
            ("", "occurred_at TIMESTAMPTZ"),
            ("", "source VARCHAR(40)"),
        ],
    },
    "airport_warehouse_state": {
        "pos": (90, 3420),
        "color": "#9333ea",
        "fields": [
            ("PK/FK", "airport_id -> airports.id"),
            ("", "current_stock INT"),
            ("", "capacity INT"),
            ("", "occupancy_percent NUMERIC(5,2)"),
            ("", "semaphore_color VARCHAR(10)"),
            ("", "updated_at TIMESTAMPTZ"),
        ],
    },
    "flight_load_state": {
        "pos": (1540, 1120),
        "color": "#0d9488",
        "fields": [
            ("PK/FK", "flight_plan_id -> flight_plans.id"),
            ("", "current_load INT"),
            ("", "capacity INT"),
            ("", "occupancy_percent NUMERIC(5,2)"),
            ("", "semaphore_color VARCHAR(10)"),
            ("", "updated_at TIMESTAMPTZ"),
        ],
    },
    "flight_cancellations": {
        "pos": (1540, 1660),
        "color": "#dc2626",
        "fields": [
            ("PK", "id UUID"),
            ("FK", "flight_plan_id -> flight_plans.id"),
            ("", "cancelled_at TIMESTAMPTZ"),
            ("", "reason TEXT"),
            ("", "affected_baggage_count INT"),
            ("", "created_by VARCHAR(120)"),
        ],
    },
    "planner_runs": {
        "pos": (1540, 2200),
        "color": "#ea580c",
        "fields": [
            ("PK", "id UUID"),
            ("", "run_type VARCHAR(30)"),
            ("", "started_at TIMESTAMPTZ"),
            ("", "finished_at TIMESTAMPTZ"),
            ("", "execution_ms INT"),
            ("", "planned_baggage_count INT"),
            ("", "used_flight_count INT"),
            ("", "parameters JSONB"),
            ("", "status VARCHAR(30)"),
        ],
    },
    "replanning_events": {
        "pos": (1540, 2960),
        "color": "#be123c",
        "fields": [
            ("PK", "id UUID"),
            ("FK", "cancellation_id -> flight_cancellations.id"),
            ("FK", "planner_run_id -> planner_runs.id"),
            ("FK", "baggage_group_id -> baggage_groups.id"),
            ("", "reason VARCHAR(120)"),
            ("FK", "old_route_plan_id -> route_plans.id"),
            ("FK", "new_route_plan_id -> route_plans.id"),
            ("", "created_at TIMESTAMPTZ"),
        ],
    },
    "route_plans": {
        "pos": (3000, 760),
        "color": "#f59e0b",
        "fields": [
            ("PK", "id UUID"),
            ("FK", "baggage_group_id -> baggage_groups.id"),
            ("FK", "planner_run_id -> planner_runs.id"),
            ("", "version INT"),
            ("", "status VARCHAR(30)"),
            ("", "reason VARCHAR(120)"),
            ("", "estimated_arrival_at TIMESTAMPTZ"),
            ("", "created_at TIMESTAMPTZ"),
        ],
    },
    "route_segments": {
        "pos": (3000, 1470),
        "color": "#d97706",
        "fields": [
            ("PK", "id UUID"),
            ("FK", "route_plan_id -> route_plans.id"),
            ("", "segment_order INT"),
            ("FK", "flight_plan_id -> flight_plans.id"),
            ("FK", "origin_airport_id -> airports.id"),
            ("FK", "destination_airport_id -> airports.id"),
            ("", "departure_time_utc TIMESTAMPTZ"),
            ("", "arrival_time_utc TIMESTAMPTZ"),
            ("", "baggage_count INT"),
            ("", "status VARCHAR(30)"),
        ],
    },
    "simulations": {
        "pos": (3000, 2330),
        "color": "#4f46e5",
        "fields": [
            ("PK", "id UUID"),
            ("", "name VARCHAR(120)"),
            ("", "scenario_type VARCHAR(40)"),
            ("", "start_datetime TIMESTAMPTZ"),
            ("", "duration_minutes INT"),
            ("", "status VARCHAR(30)"),
            ("", "simulated_now TIMESTAMPTZ"),
            ("", "created_at TIMESTAMPTZ"),
        ],
    },
    "simulation_events": {
        "pos": (3000, 3030),
        "color": "#6366f1",
        "fields": [
            ("PK", "id UUID"),
            ("FK", "simulation_id -> simulations.id"),
            ("", "event_type VARCHAR(40)"),
            ("", "event_time TIMESTAMPTZ"),
            ("", "payload JSONB"),
            ("", "created_at TIMESTAMPTZ"),
        ],
    },
    "system_parameters": {
        "pos": (3000, 3600),
        "color": "#64748b",
        "fields": [
            ("PK", "key VARCHAR(80)"),
            ("", "value JSONB"),
            ("", "description TEXT"),
            ("", "updated_at TIMESTAMPTZ"),
        ],
    },
    "audit_logs": {
        "pos": (4310, 3600),
        "color": "#64748b",
        "fields": [
            ("PK", "id UUID"),
            ("", "actor VARCHAR(120)"),
            ("", "action VARCHAR(80)"),
            ("", "entity_type VARCHAR(80)"),
            ("", "entity_id UUID"),
            ("", "payload JSONB"),
            ("", "created_at TIMESTAMPTZ"),
        ],
    },
}


VIEWS = {
    "vw_airport_map_state": {
        "pos": (4310, 520),
        "fields": [
            "airports + airport_warehouse_state",
            "estado y ocupación por aeropuerto",
            "uso: mapa principal",
        ],
    },
    "vw_flight_map_state": {
        "pos": (4310, 940),
        "fields": [
            "flight_plans + airports + flight_load_state",
            "rutas y ocupación de vuelos",
            "uso: animación de vuelos activos",
        ],
    },
    "vw_shipment_tracking": {
        "pos": (4310, 1360),
        "fields": [
            "shipments + baggage_groups",
            "airports + flight_plans",
            "uso: búsqueda y tracking de envío",
        ],
    },
}


RELATIONS = [
    ("airports", "flight_plans", "1:N origen"),
    ("airports", "flight_plans", "1:N destino"),
    ("airports", "shipments", "1:N origen"),
    ("airports", "shipments", "1:N destino"),
    ("shipments", "baggage_groups", "1:N"),
    ("airports", "baggage_groups", "current"),
    ("flight_plans", "baggage_groups", "current"),
    ("baggage_groups", "route_plans", "1:N"),
    ("planner_runs", "route_plans", "1:N"),
    ("route_plans", "route_segments", "1:N"),
    ("flight_plans", "route_segments", "1:N"),
    ("airports", "route_segments", "origen/destino"),
    ("airports", "warehouse_movements", "1:N"),
    ("baggage_groups", "warehouse_movements", "1:N"),
    ("flight_plans", "warehouse_movements", "1:N"),
    ("airports", "airport_warehouse_state", "1:1"),
    ("flight_plans", "flight_load_state", "1:1"),
    ("flight_plans", "flight_cancellations", "1:N"),
    ("flight_cancellations", "replanning_events", "1:N"),
    ("planner_runs", "replanning_events", "1:N"),
    ("baggage_groups", "replanning_events", "1:N"),
    ("route_plans", "replanning_events", "old/new"),
    ("simulations", "simulation_events", "1:N"),
]


VIEW_RELATIONS = [
    ("airports", "vw_airport_map_state"),
    ("airport_warehouse_state", "vw_airport_map_state"),
    ("flight_plans", "vw_flight_map_state"),
    ("flight_load_state", "vw_flight_map_state"),
    ("shipments", "vw_shipment_tracking"),
    ("baggage_groups", "vw_shipment_tracking"),
    ("flight_plans", "vw_shipment_tracking"),
]


def table_size(fields):
    return 800, 86 + len(fields) * 32


def center(name):
    data = TABLES.get(name) or VIEWS[name]
    x, y = data["pos"]
    w, h = table_size(data["fields"])
    return x + w / 2, y + h / 2


def edge_point(name, toward):
    data = TABLES.get(name) or VIEWS[name]
    x, y = data["pos"]
    w, h = table_size(data["fields"])
    cx, cy = x + w / 2, y + h / 2
    tx, ty = toward
    dx, dy = tx - cx, ty - cy
    if abs(dx) / w > abs(dy) / h:
        px = x + (w if dx > 0 else 0)
        py = cy + dy * ((w / 2) / abs(dx)) if dx else cy
    else:
        py = y + (h if dy > 0 else 0)
        px = cx + dx * ((h / 2) / abs(dy)) if dy else cx
    return px, py


def draw_arrow(draw, src, dst, label="", color="#64748b", width=3, dashed=False):
    c1 = center(src)
    c2 = center(dst)
    p1 = edge_point(src, c2)
    p2 = edge_point(dst, c1)
    x1, y1 = p1
    x2, y2 = p2

    if dashed:
        draw_dashed_line(draw, p1, p2, fill=color, width=width)
    else:
        draw.line([p1, p2], fill=color, width=width)

    angle = math.atan2(y2 - y1, x2 - x1)
    size = 18
    left = (x2 - size * math.cos(angle - math.pi / 7), y2 - size * math.sin(angle - math.pi / 7))
    right = (x2 - size * math.cos(angle + math.pi / 7), y2 - size * math.sin(angle + math.pi / 7))
    draw.polygon([p2, left, right], fill=color)

    if label:
        mx, my = (x1 + x2) / 2, (y1 + y2) / 2
        bbox = draw.textbbox((0, 0), label, font=LEGEND)
        tw, th = bbox[2] - bbox[0], bbox[3] - bbox[1]
        draw.rounded_rectangle([mx - tw / 2 - 8, my - th / 2 - 5, mx + tw / 2 + 8, my + th / 2 + 5],
                               radius=8, fill="#ffffff", outline="#e2e8f0")
        draw.text((mx - tw / 2, my - th / 2 - 1), label, font=LEGEND, fill="#334155")


def draw_dashed_line(draw, p1, p2, fill, width):
    x1, y1 = p1
    x2, y2 = p2
    dist = math.hypot(x2 - x1, y2 - y1)
    if dist == 0:
        return
    dash, gap = 24, 14
    steps = int(dist / (dash + gap)) + 1
    for i in range(steps):
        start = i * (dash + gap)
        end = min(start + dash, dist)
        if start >= dist:
            break
        sx = x1 + (x2 - x1) * (start / dist)
        sy = y1 + (y2 - y1) * (start / dist)
        ex = x1 + (x2 - x1) * (end / dist)
        ey = y1 + (y2 - y1) * (end / dist)
        draw.line([(sx, sy), (ex, ey)], fill=fill, width=width)


def draw_table(draw, name, data):
    x, y = data["pos"]
    fields = data["fields"]
    w, h = table_size(fields)
    color = data.get("color", "#475569")

    draw.rounded_rectangle([x + 10, y + 12, x + w + 10, y + h + 12], radius=18, fill="#cbd5e1")
    draw.rounded_rectangle([x, y, x + w, y + h], radius=18, fill="#ffffff", outline="#94a3b8", width=3)
    draw.rounded_rectangle([x, y, x + w, y + 58], radius=18, fill=color)
    draw.rectangle([x, y + 35, x + w, y + 58], fill=color)
    draw.text((x + 22, y + 15), name, font=HEADER, fill="#ffffff")

    yy = y + 72
    for tag, text in fields:
        if tag:
            tag_w = 64 if tag != "PK/FK" else 82
            tag_color = {
                "PK": "#111827",
                "FK": "#2563eb",
                "UK": "#7c3aed",
                "PK/FK": "#0f766e",
            }.get(tag, "#475569")
            draw.rounded_rectangle([x + 18, yy - 3, x + 18 + tag_w, yy + 23], radius=7, fill=tag_color)
            draw.text((x + 26, yy + 1), tag, font=TAG, fill="#ffffff")
            text_x = x + 34 + tag_w
            fnt = FIELD_BOLD if tag in {"PK", "PK/FK"} else FIELD
        else:
            text_x = x + 28
            fnt = FIELD
        draw.text((text_x, yy), text, font=fnt, fill="#0f172a")
        yy += 32


def draw_view(draw, name, data):
    x, y = data["pos"]
    fields = data["fields"]
    w, h = table_size(fields)
    draw.rounded_rectangle([x + 10, y + 12, x + w + 10, y + h + 12], radius=18, fill="#cbd5e1")
    draw.rounded_rectangle([x, y, x + w, y + h], radius=18, fill="#ffffff", outline="#38bdf8", width=3)
    draw.rounded_rectangle([x, y, x + w, y + 58], radius=18, fill="#0284c7")
    draw.rectangle([x, y + 35, x + w, y + 58], fill="#0284c7")
    draw.text((x + 22, y + 15), name, font=HEADER, fill="#ffffff")
    yy = y + 78
    for item in fields:
        draw.text((x + 28, yy), item, font=FIELD, fill="#0f172a")
        yy += 34


def main():
    OUT.parent.mkdir(parents=True, exist_ok=True)
    img = Image.new("RGB", (W, H), "#ffffff")
    draw = ImageDraw.Draw(img)

    draw.text((90, 55), "Esquema de Base de Datos TASF.B2B", font=TITLE, fill="#0f172a")
    draw.text((90, 116), "Aeropuertos, vuelos, envíos, planificación, almacenes, replanificación, simulación y vistas para frontend",
              font=SUBTITLE, fill="#475569")

    legend_x = 4310
    draw.rounded_rectangle([legend_x, 245, legend_x + 1570, 395], radius=18, fill="#f8fafc", outline="#cbd5e1", width=2)
    draw.text((legend_x + 25, 270), "Leyenda", font=HEADER, fill="#0f172a")
    for i, (tag, desc, color) in enumerate([
        ("PK", "clave primaria", "#111827"),
        ("FK", "clave foránea", "#2563eb"),
        ("UK", "valor único", "#7c3aed"),
        ("-->", "relación entre tablas", "#64748b"),
    ]):
        x = legend_x + 180 + i * 335
        if tag == "-->":
            draw.line([(x, 303), (x + 48, 303)], fill=color, width=4)
            draw.polygon([(x + 48, 303), (x + 34, 294), (x + 34, 312)], fill=color)
            draw.text((x + 62, 292), desc, font=LEGEND, fill="#334155")
        else:
            draw.rounded_rectangle([x, 288, x + 58, 318], radius=8, fill=color)
            draw.text((x + 12, 293), tag, font=TAG, fill="#ffffff")
            draw.text((x + 70, 292), desc, font=LEGEND, fill="#334155")

    for src, dst, label in RELATIONS:
        draw_arrow(draw, src, dst, label, color="#64748b", width=3)
    for src, dst in VIEW_RELATIONS:
        draw_arrow(draw, src, dst, "", color="#0ea5e9", width=3, dashed=True)

    for name, data in TABLES.items():
        draw_table(draw, name, data)
    for name, data in VIEWS.items():
        draw_view(draw, name, data)

    draw.text((4310, 1850), "Vistas de consulta", font=HEADER, fill="#0f172a")
    draw.text((4310, 1890), "Las líneas azules punteadas indican qué tablas alimentan cada vista.",
              font=LEGEND, fill="#475569")
    draw.text((4310, 1965), "Nota: system_parameters y audit_logs son tablas transversales; no tienen FK directa en la propuesta base.",
              font=LEGEND, fill="#475569")

    img.save(OUT, "PNG", optimize=True)
    print(OUT.resolve())


if __name__ == "__main__":
    main()
