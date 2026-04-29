"""
Tasf.B2B — Planificador de rutas de maletas  (A*)
==================================================

Correcciones aplicadas respecto a la versión anterior:

[FIX-1] ZONAS HORARIAS
  Los horarios en vuelos.txt están en hora LOCAL del aeropuerto
  (origen para la salida, destino para la llegada).
  Ahora cada hora se convierte a UTC antes de cualquier comparación,
  usando el offset leído del campo correspondiente en aeropuertos.txt.
  Todo el A* opera íntegramente en UTC.

[FIX-2] CAPACIDAD DE VUELO COMPARTIDA
  Cada vuelo tiene una capacidad máxima de maletas (campo cap en vuelos.txt).
  Ese límite ahora se descuenta globalmente conforme se asignan envíos.
  La estructura `EstadoVuelos` es compartida entre todos los envíos y
  protegida con un threading.Lock para evitar condiciones de carrera.

[FIX-3] UN VUELO PUEDE LLEVAR MÚLTIPLES ENVÍOS
  La planificación procesa los envíos en orden cronológico (UTC) y descuenta
  maletas del vuelo asignado. Si un vuelo ya está lleno, A* lo descarta
  y busca el siguiente disponible. Un mismo vuelo puede acumular
  envíos de distintas líneas hasta agotar su capacidad.
"""

import heapq
import re
import math
import os
import sys
import time
import argparse
import threading
import concurrent.futures
from datetime import datetime, timedelta
from collections import defaultdict


# ══════════════════════════════════════════════════════════════════════════════
# HAVERSINE + HEURÍSTICA
# ══════════════════════════════════════════════════════════════════════════════

def haversine(lat1, lon1, lat2, lon2):
    R = 6371
    phi1, phi2 = math.radians(lat1), math.radians(lat2)
    dphi    = math.radians(lat2 - lat1)
    dlambda = math.radians(lon2 - lon1)
    a = math.sin(dphi/2)**2 + math.cos(phi1)*math.cos(phi2)*math.sin(dlambda/2)**2
    return 2 * R * math.atan2(math.sqrt(a), math.sqrt(1 - a))

def heuristic(a, b, aeropuertos):
    if a not in aeropuertos or b not in aeropuertos:
        return 0.0
    lat1, lon1 = aeropuertos[a]["lat"], aeropuertos[a]["lon"]
    lat2, lon2 = aeropuertos[b]["lat"], aeropuertos[b]["lon"]
    # Admisible: distancia / velocidad máxima de referencia
    return haversine(lat1, lon1, lat2, lon2) / 900


# ══════════════════════════════════════════════════════════════════════════════
# [FIX-1] CONVERSIÓN LOCAL ↔ UTC
# Todos los tiempos internos se manejan como UTC naive.
# UTC = hora_local − offset_aeropuerto
# ══════════════════════════════════════════════════════════════════════════════

def local_a_utc(dt_local, offset_horas):
    """Hora local (naive) → UTC (naive).  offset_horas: p.ej. -5 (Bogotá), +2 (Berlín)."""
    return dt_local - timedelta(hours=offset_horas)

def utc_a_local(dt_utc, offset_horas):
    """UTC (naive) → hora local (naive)."""
    return dt_utc + timedelta(hours=offset_horas)


# ══════════════════════════════════════════════════════════════════════════════
# CAPACIDAD DE ALMACÉN DE AEROPUERTO
# ══════════════════════════════════════════════════════════════════════════════

def hay_capacidad_aeropuerto(aeropuerto, tiempo_utc, cantidad, capacidad_aeropuerto):
    """Comprueba si el almacén tiene espacio en ventana de ±1 h alrededor de la llegada."""
    ventana  = timedelta(hours=1)
    ocupados = sum(
        cant
        for t, cant in capacidad_aeropuerto[aeropuerto]["ocupacion"]
        if abs(t - tiempo_utc) <= ventana
    )
    return (ocupados + cantidad) <= capacidad_aeropuerto[aeropuerto]["max"]

def registrar_ocupacion_aeropuerto(aeropuerto, tiempo_utc, cantidad, capacidad_aeropuerto):
    capacidad_aeropuerto[aeropuerto]["ocupacion"].append((tiempo_utc, cantidad))


# ══════════════════════════════════════════════════════════════════════════════
# [FIX-2 + FIX-3]  ESTADO COMPARTIDO DE CAPACIDAD DE VUELOS
# ══════════════════════════════════════════════════════════════════════════════

def _clave_vuelo(vuelo):
    """
    Identificador único de un slot de vuelo.
    (origen, destino, HH:MM salida local) — suficiente porque el archivo
    de vuelos no tiene fecha (los vuelos se repiten diariamente).
    """
    return (
        vuelo["origen"],
        vuelo["destino"],
        vuelo["salida_local"].strftime("%H:%M"),
    )


class EstadoVuelos:
    """
    Registra cuántas maletas lleva cada vuelo.
    Compartido entre todos los envíos, thread-safe.
    """

    def __init__(self, vuelos_lista):
        self._lock   = threading.Lock()
        self._estado = {}
        for v in vuelos_lista:
            clave = _clave_vuelo(v)
            # Si la misma ruta/horario aparece varias veces en el archivo,
            # tomamos la primera ocurrencia (los datos son consistentes).
            if clave not in self._estado:
                self._estado[clave] = {"cap_max": v["capacidad"], "ocupado": 0}

    def hay_capacidad(self, vuelo, cantidad):
        clave = _clave_vuelo(vuelo)
        with self._lock:
            e = self._estado.get(clave)
            return e is not None and (e["ocupado"] + cantidad) <= e["cap_max"]

    def reservar(self, vuelo, cantidad):
        """Descuenta `cantidad` maletas del vuelo. Devuelve True si tuvo éxito."""
        clave = _clave_vuelo(vuelo)
        with self._lock:
            e = self._estado.get(clave)
            if e is None or (e["ocupado"] + cantidad) > e["cap_max"]:
                return False
            e["ocupado"] += cantidad
            return True

    def liberar(self, vuelo, cantidad):
        """Rollback: devuelve maletas al vuelo."""
        clave = _clave_vuelo(vuelo)
        with self._lock:
            e = self._estado.get(clave)
            if e:
                e["ocupado"] = max(0, e["ocupado"] - cantidad)

    def ocupacion(self, vuelo):
        clave = _clave_vuelo(vuelo)
        with self._lock:
            e = self._estado.get(clave, {})
            return e.get("ocupado", 0), e.get("cap_max", 0)


# ══════════════════════════════════════════════════════════════════════════════
# PARSEO DE COORDENADAS
# ══════════════════════════════════════════════════════════════════════════════

def dms_a_decimal(grados, minutos, segundos, direccion):
    decimal = grados + minutos / 60 + segundos / 3600
    if direccion in ("S", "W"):
        decimal *= -1
    return decimal

def parsear_coordenadas(linea):
    lat_m = re.search(r"Latitude:\s*(\d+)°\s*(\d+)'\s*(\d+)\"?\s*([NS])", linea)
    lon_m = re.search(r"Longitude:\s*(\d+)°\s*(\d+)'\s*(\d+)\"?\s*([EW])", linea)
    if not lat_m or not lon_m:
        return None, None
    lat = dms_a_decimal(*map(int, lat_m.groups()[:3]), lat_m.group(4))
    lon = dms_a_decimal(*map(int, lon_m.groups()[:3]), lon_m.group(4))
    return lat, lon


# ══════════════════════════════════════════════════════════════════════════════
# LEER AEROPUERTOS  — ahora extrae también el offset UTC
# ══════════════════════════════════════════════════════════════════════════════

def leer_aeropuertos(path):
    """
    Devuelve:
      aeropuertos : {ICAO: {lat, lon, tz_offset}}
      capacidad   : {ICAO: {max, ocupacion: [(t_utc, maletas), ...]}}
    """
    aeropuertos = {}
    capacidad   = {}
    with open(path, "r", encoding="utf-16") as f:
        for linea in f:
            partes = linea.split()
            if len(partes) < 3:
                continue
            codigo = partes[1]
            try:
                lat_idx   = next(i for i, p in enumerate(partes) if p.startswith("Latitude"))
                cap_max   = int(partes[lat_idx - 1])
                tz_offset = int(partes[lat_idx - 2])
            except (StopIteration, ValueError, IndexError):
                continue
            lat, lon = parsear_coordenadas(linea)
            if lat is None:
                continue
            aeropuertos[codigo] = {"lat": lat, "lon": lon, "tz_offset": tz_offset}
            capacidad[codigo]   = {"max": cap_max, "ocupacion": []}
    return aeropuertos, capacidad

def clonar_capacidad(capacidad_original):
    return {
        k: {"max": v["max"], "ocupacion": list(v["ocupacion"])}
        for k, v in capacidad_original.items()
    }


# ══════════════════════════════════════════════════════════════════════════════
# LEER VUELOS  — [FIX-1] convierte horarios a UTC
# ══════════════════════════════════════════════════════════════════════════════

def leer_vuelos(path, aeropuertos):
    """
    Formato: ORIG-DEST-HO:MO-HD:MD-CAP
      HO:MO = hora salida en hora LOCAL del aeropuerto ORIGEN
      HD:MD = hora llegada en hora LOCAL del aeropuerto DESTINO
    """
    vuelos = []
    with open(path, "r", encoding="utf-8") as f:
        for linea in f:
            linea = linea.strip()
            if not linea:
                continue
            partes = linea.split("-")
            if len(partes) != 5:
                continue
            origen, destino, h1, h2, cap = partes
            try:
                salida_local  = datetime.strptime(h1, "%H:%M")
                llegada_local = datetime.strptime(h2, "%H:%M")
                capacidad_v   = int(cap)
            except ValueError:
                continue

            tz_orig = aeropuertos.get(origen,  {}).get("tz_offset", 0)
            tz_dest = aeropuertos.get(destino, {}).get("tz_offset", 0)

            # [FIX-1] Convertir a UTC
            salida_utc  = local_a_utc(salida_local,  tz_orig)
            llegada_utc = local_a_utc(llegada_local, tz_dest)

            vuelos.append({
                "origen":        origen,
                "destino":       destino,
                "salida_local":  salida_local,   # hora local origen  (clave + reporte)
                "llegada_local": llegada_local,  # hora local destino (reporte)
                "salida_utc":    salida_utc,     # ← A* usa esto
                "llegada_utc":   llegada_utc,    # ← A* usa esto
                "capacidad":     capacidad_v,
                "tz_orig":       tz_orig,
                "tz_dest":       tz_dest,
            })
    return vuelos


# ══════════════════════════════════════════════════════════════════════════════
# LEER ENVÍOS  — [FIX-1] convierte hora de recepción a UTC
# ══════════════════════════════════════════════════════════════════════════════

def leer_envios(path, aeropuertos):
    """
    Formato: id_envio-aaaammdd-hh-mm-dest-cant-IdCliente
    hh-mm = hora LOCAL del aeropuerto origen (inferido del nombre del archivo).
    """
    nombre = os.path.basename(path)
    m = re.search(r"_envios_([A-Z0-9]{3,4})_", nombre, re.IGNORECASE)
    origen_archivo = m.group(1).upper() if m else "????"
    tz_origen = aeropuertos.get(origen_archivo, {}).get("tz_offset", 0)

    envios          = []
    errores_formato = []

    with open(path, "r", encoding="utf-8") as f:
        for num_linea, linea in enumerate(f, 1):
            linea = linea.strip()
            if not linea:
                continue
            partes = linea.split("-")
            if len(partes) != 7:
                errores_formato.append((num_linea, linea, "Número de campos incorrecto"))
                continue
            id_envio, fecha_str, hh, mm, dest, cant_str, id_cliente = partes
            try:
                fecha      = datetime.strptime(fecha_str, "%Y%m%d")
                hora_local = fecha.replace(hour=int(hh), minute=int(mm))
                hora_utc   = local_a_utc(hora_local, tz_origen)
                cantidad   = int(cant_str)
            except (ValueError, OverflowError) as e:
                errores_formato.append((num_linea, linea, str(e)))
                continue

            envios.append({
                "id_envio":   id_envio,
                "origen":     origen_archivo,
                "destino":    dest,
                "hora_local": hora_local,   # para el reporte
                "hora_utc":   hora_utc,     # para A*
                "cantidad":   cantidad,
                "id_cliente": id_cliente,
                "linea":      num_linea,
                "raw":        linea,
            })

    return envios, origen_archivo, errores_formato


# ══════════════════════════════════════════════════════════════════════════════
# ALGORITMO A*  (opera en UTC; valida cap. vuelo compartida)
# ══════════════════════════════════════════════════════════════════════════════

class Nodo:
    __slots__ = ("aeropuerto", "tiempo_utc", "costo", "padre", "vuelo")

    def __init__(self, aeropuerto, tiempo_utc, costo, padre=None, vuelo=None):
        self.aeropuerto = aeropuerto
        self.tiempo_utc = tiempo_utc
        self.costo      = costo
        self.padre      = padre
        self.vuelo      = vuelo

    def __lt__(self, other):
        return self.costo < other.costo


def a_star(origen, destino, vuelos, aeropuertos,
           capacidad_aeropuerto, estado_vuelos,
           tiempo_inicio_utc, cantidad_maletas):
    """
    Devuelve (ruta, vuelos_usados, costo_horas, reservas_hechas).
    Si no hay ruta: (None, None, inf, []).
    reservas_hechas: lista de (vuelo, cantidad) para poder hacer rollback.
    """
    if origen == destino:
        return [origen], [], 0.0, []

    def reconstruir(nodo):
        ruta, vls = [], []
        while nodo:
            ruta.append(nodo.aeropuerto)
            if nodo.vuelo:
                vls.append(nodo.vuelo)
            nodo = nodo.padre
        return list(reversed(ruta)), list(reversed(vls))

    vuelos_por_origen = defaultdict(list)
    for v in vuelos:
        vuelos_por_origen[v["origen"]].append(v)

    abiertos        = []
    visitados       = {}
    reservas_hechas = []

    heapq.heappush(abiertos, (0, Nodo(origen, tiempo_inicio_utc, 0)))

    while abiertos:
        _, actual = heapq.heappop(abiertos)

        if actual.aeropuerto == destino:
            ruta, vus = reconstruir(actual)
            return ruta, vus, actual.costo, reservas_hechas

        clave = (actual.aeropuerto, actual.tiempo_utc)
        if clave in visitados and visitados[clave] <= actual.costo:
            continue
        visitados[clave] = actual.costo

        for vuelo in vuelos_por_origen[actual.aeropuerto]:

            # ── [FIX-1] Anclar UTC al día del nodo actual ──────────────────
            salida_utc  = vuelo["salida_utc"].replace(
                year=actual.tiempo_utc.year,
                month=actual.tiempo_utc.month,
                day=actual.tiempo_utc.day,
            )
            llegada_utc = vuelo["llegada_utc"].replace(
                year=actual.tiempo_utc.year,
                month=actual.tiempo_utc.month,
                day=actual.tiempo_utc.day,
            )

            # Vuelo cruza medianoche UTC
            if llegada_utc < salida_utc:
                llegada_utc += timedelta(days=1)

            # Vuelo ya salió en UTC → diferir al día siguiente
            if salida_utc < actual.tiempo_utc:
                salida_utc  += timedelta(days=1)
                llegada_utc += timedelta(days=1)

            espera   = (salida_utc  - actual.tiempo_utc).total_seconds() / 3600
            duracion = (llegada_utc - salida_utc).total_seconds()        / 3600

            if espera < 0 or duracion <= 0:
                continue

            dest_vuelo = vuelo["destino"]
            if dest_vuelo not in capacidad_aeropuerto:
                continue

            # ── [FIX-2] Capacidad del vuelo (compartida, thread-safe) ──────
            if not estado_vuelos.hay_capacidad(vuelo, cantidad_maletas):
                continue  # vuelo lleno

            # ── Capacidad del almacén destino (copia local) ────────────────
            if not hay_capacidad_aeropuerto(
                dest_vuelo, llegada_utc, cantidad_maletas, capacidad_aeropuerto
            ):
                continue

            # ── [FIX-3] Reserva atómica en el vuelo compartido ─────────────
            if not estado_vuelos.reservar(vuelo, cantidad_maletas):
                # Otro hilo llegó primero; vuelo ya no tiene espacio
                continue
            reservas_hechas.append((vuelo, cantidad_maletas))

            # Registrar en almacén local
            registrar_ocupacion_aeropuerto(
                dest_vuelo, llegada_utc, cantidad_maletas, capacidad_aeropuerto
            )

            nuevo_costo = actual.costo + espera + duracion
            h           = heuristic(dest_vuelo, destino, aeropuertos)

            heapq.heappush(
                abiertos,
                (nuevo_costo + h,
                 Nodo(dest_vuelo, llegada_utc, nuevo_costo, actual, vuelo))
            )

    return None, None, float("inf"), reservas_hechas


# ══════════════════════════════════════════════════════════════════════════════
# PLANIFICACIÓN POR LOTES
# ══════════════════════════════════════════════════════════════════════════════

def planificar_envio(envio, vuelos, aeropuertos, capacidad_base, estado_vuelos):
    """
    Planifica un único envío.
    - Almacenes: copia local independiente por envío.
    - Capacidad de vuelos: estado_vuelos compartido.
    """
    cap_local = clonar_capacidad(capacidad_base)
    ruta, vuelos_us, costo, reservas = a_star(
        origen               = envio["origen"],
        destino              = envio["destino"],
        vuelos               = vuelos,
        aeropuertos          = aeropuertos,
        capacidad_aeropuerto = cap_local,
        estado_vuelos        = estado_vuelos,
        tiempo_inicio_utc    = envio["hora_utc"],
        cantidad_maletas     = envio["cantidad"],
    )
    if ruta is None:
        # Rollback de reservas parciales hechas durante la búsqueda fallida
        for v, cant in reservas:
            estado_vuelos.liberar(v, cant)
    return envio, ruta, vuelos_us, costo, estado_vuelos


def planificar_lote(envios, vuelos, aeropuertos, capacidad_base,
                    max_workers=4, mostrar_progreso=True):
    """
    [FIX-3] Ordena envíos por hora UTC (FIFO) antes de planificar,
    para que los que llegan primero tengan prioridad en reservar vuelos.
    """
    envios_ord    = sorted(envios, key=lambda e: e["hora_utc"])
    estado_vuelos = EstadoVuelos(vuelos)   # compartido entre todos los envíos

    resultados_ok   = []
    resultados_fail = []
    total = len(envios_ord)
    t0    = time.time()

    with concurrent.futures.ThreadPoolExecutor(max_workers=max_workers) as executor:
        futuros = {
            executor.submit(
                planificar_envio, env, vuelos, aeropuertos,
                capacidad_base, estado_vuelos
            ): env
            for env in envios_ord
        }

        for i, fut in enumerate(concurrent.futures.as_completed(futuros), 1):
            try:
                envio, ruta, vuelos_us, costo, ev = fut.result()

                if ruta is None:
                    resultados_fail.append({
                        "envio":  envio,
                        "motivo": "Sin ruta disponible (A* sin solución)",
                    })
                else:
                    mismo_cont   = _mismo_continente(envio["origen"], envio["destino"])
                    plazo_dias   = 1 if mismo_cont else 2
                    dias_viaje   = costo / 24
                    cumple_plazo = dias_viaje <= plazo_dias

                    # Ocupación de vuelos usados (informativa para el reporte)
                    ocupacion_vuelos = []
                    for v in vuelos_us:
                        ocup, cap_max = ev.ocupacion(v)
                        ocupacion_vuelos.append((ocup, cap_max))

                    resultados_ok.append({
                        "envio":            envio,
                        "ruta":             ruta,
                        "vuelos_usados":    vuelos_us,
                        "ocupacion_vuelos": ocupacion_vuelos,
                        "costo_horas":      costo,
                        "dias_viaje":       dias_viaje,
                        "plazo_dias":       plazo_dias,
                        "cumple_plazo":     cumple_plazo,
                        "escalas":          len(ruta) - 2,
                    })

                if mostrar_progreso and (i % 100 == 0 or i == total):
                    elapsed = time.time() - t0
                    eta     = (elapsed / i) * (total - i)
                    print(
                        f"\r  [{i:>7}/{total}]  {i/total*100:5.1f}%  "
                        f"Elapsed: {_fmt_tiempo(elapsed)}  ETA: {_fmt_tiempo(eta)}  "
                        f"OK: {len(resultados_ok)}  Fail: {len(resultados_fail)}",
                        end="", flush=True,
                    )

            except Exception as exc:
                env = futuros[fut]
                resultados_fail.append({"envio": env, "motivo": f"Excepción: {exc}"})

    if mostrar_progreso:
        print()

    return resultados_ok, resultados_fail


# ══════════════════════════════════════════════════════════════════════════════
# UTILIDADES
# ══════════════════════════════════════════════════════════════════════════════

_PREFIJOS = {
    "S": "América del Sur",   "K": "América del Norte",
    "M": "América Central",   "T": "Caribe",
    "E": "Europa Norte",      "L": "Europa Sur/Med.",
    "U": "Europa Este / Asia Central",
    "O": "Medio Oriente / Asia Sur",
    "V": "Asia Sur / Sureste","R": "Asia del Este",
    "W": "Asia Sureste",      "Y": "Oceanía",
    "F": "África Sur",        "G": "África Oeste",
    "H": "África Este/Norte", "D": "África Oeste 2",
    "Z": "China",
}

def _continente(icao):
    return _PREFIJOS.get(icao[0].upper(), "Desconocido")

def _mismo_continente(orig, dest):
    return _continente(orig) == _continente(dest)

def _fmt_tiempo(s):
    h = int(s // 3600); m = int((s % 3600) // 60); s2 = int(s % 60)
    return f"{h}h {m:02d}m {s2:02d}s" if h else f"{m:02d}m {s2:02d}s"

def _fmt_vuelo(v, ocup=None, cap_max=None):
    tz_o = v.get("tz_orig", 0)
    tz_d = v.get("tz_dest", 0)
    ocup_str = f"  [ocup. {ocup}/{cap_max}]" if ocup is not None else ""
    return (
        f"{v['origen']}→{v['destino']}  "
        f"sal. {v['salida_local'].strftime('%H:%M')} local (UTC{tz_o:+d})"
        f" / lleg. {v['llegada_local'].strftime('%H:%M')} local (UTC{tz_d:+d})"
        f"  cap={v['capacidad']}{ocup_str}"
    )


# ══════════════════════════════════════════════════════════════════════════════
# GENERACIÓN DE REPORTE
# ══════════════════════════════════════════════════════════════════════════════

def generar_reporte(resultados_ok, resultados_fail, errores_formato,
                    origen_archivo, path_salida, vuelos, aeropuertos):

    total     = len(resultados_ok) + len(resultados_fail)
    n_ok      = len(resultados_ok)
    n_fail    = len(resultados_fail)
    n_err     = len(errores_formato)
    n_cumple  = sum(1 for r in resultados_ok if r["cumple_plazo"])
    horas_l   = [r["costo_horas"] for r in resultados_ok]
    esc_l     = [r["escalas"]     for r in resultados_ok]
    timestamp = datetime.now().strftime("%Y-%m-%d %H:%M:%S")

    SM = "═" * 100
    Sm = "─" * 100
    Ss = "·" * 100
    L  = []
    def w(*args): L.extend(args)

    w(SM)
    w("  TASF.B2B — REPORTE DE PLANIFICACIÓN DE RUTAS DE MALETAS")
    w(SM)
    w(f"  Generado       : {timestamp}")
    w(f"  Origen archivo : {origen_archivo}")
    w(f"  Algoritmo      : A* con heurística Haversine (ref. 900 km/h)")
    w(f"  [FIX-1]        : Horarios convertidos a UTC antes de comparar")
    w(f"  [FIX-2]        : Capacidad de vuelo compartida entre todos los envíos")
    w(f"  [FIX-3]        : Un vuelo puede transportar varios envíos simultáneos")
    w(f"  Política       : mismo continente ≤ 1 día  |  distinto continente ≤ 2 días")
    w(Sm)
    w("")
    w("  ┌──────────────────────────────────┐")
    w("  │      RESUMEN EJECUTIVO           │")
    w("  └──────────────────────────────────┘")
    w(f"  Total de envíos procesados       : {total:>10,}")
    w(f"  Rutas encontradas (OK)           : {n_ok:>10,}   ({n_ok/total*100:5.1f}%)" if total else "")
    w(f"  Sin ruta (FALLIDOS)              : {n_fail:>10,}   ({n_fail/total*100:5.1f}%)" if total else "")
    w(f"  Errores de formato               : {n_err:>10,}")
    w("")
    if n_ok:
        w(f"  Cumplen plazo de entrega         : {n_cumple:>10,}   ({n_cumple/n_ok*100:5.1f}% de OK)")
        w(f"  Incumplen plazo                  : {n_ok-n_cumple:>10,}   ({(n_ok-n_cumple)/n_ok*100:5.1f}% de OK)")
        w(f"  Tiempo promedio de viaje (UTC)   : {sum(horas_l)/n_ok:>9.2f}h")
        w(f"  Tiempo mínimo / máximo           : {min(horas_l):>9.2f}h  /  {max(horas_l):.2f}h")
        w(f"  Escalas promedio                 : {sum(esc_l)/n_ok:>9.2f}")
        dest_c = defaultdict(int)
        for r in resultados_ok: dest_c[r["envio"]["destino"]] += 1
        w("")
        w("  Top 5 destinos:")
        for d, c in sorted(dest_c.items(), key=lambda x: -x[1])[:5]:
            w(f"    {d}  →  {c:,} envíos")
    w("")
    w(SM)

    # SECCIÓN 1 ────────────────────────────────────────────────────────────
    w("")
    w(f"  SECCIÓN 1 — RUTAS PLANIFICADAS ({n_ok:,} envíos)")
    w(Sm)
    w("")
    for i, res in enumerate(resultados_ok, 1):
        env  = res["envio"]
        tz_o = aeropuertos.get(env["origen"], {}).get("tz_offset", 0)
        tipo = "Mismo continente" if _mismo_continente(env["origen"], env["destino"]) else "Intercontinental"
        w(f"  [{i:>8}]  Envío {env['id_envio']}  ·  Cliente {env['id_cliente']}")
        w(f"           Origen   : {env['origen']} (UTC{tz_o:+d})  [{_continente(env['origen'])}]")
        w(f"           Destino  : {env['destino']}  [{_continente(env['destino'])}]")
        w(f"           Recepción: {env['hora_local'].strftime('%Y-%m-%d %H:%M')} local"
          f"  →  {env['hora_utc'].strftime('%Y-%m-%d %H:%M')} UTC")
        w(f"           Cantidad : {env['cantidad']:>3} maleta(s)")
        w(f"           Tipo     : {tipo}  |  Plazo: {res['plazo_dias']} día(s)"
          f"  |  Viaje: {res['costo_horas']:.2f}h ({res['dias_viaje']:.3f} días)"
          f"  |  {'✔ CUMPLE' if res['cumple_plazo'] else '✘ FUERA DE PLAZO'}")
        w(f"           Ruta     : {' → '.join(res['ruta'])}")
        if res["vuelos_usados"]:
            w(f"           Vuelos   :")
            for j, (v, (ocup, cap_max)) in enumerate(
                zip(res["vuelos_usados"], res["ocupacion_vuelos"]), 1
            ):
                w(f"             {j}. {_fmt_vuelo(v, ocup, cap_max)}")
        if i < n_ok:
            w(Ss)
    w("")
    w(SM)

    # SECCIÓN 2 ────────────────────────────────────────────────────────────
    w("")
    w(f"  SECCIÓN 2 — ENVÍOS SIN RUTA ({n_fail:,} registros)")
    w(Sm)
    w("")
    if n_fail == 0:
        w("  (Ningún envío quedó sin ruta.)")
    else:
        motivos = defaultdict(int)
        for r in resultados_fail: motivos[r["motivo"]] += 1
        w("  Causas:")
        for mot, cnt in sorted(motivos.items(), key=lambda x: -x[1]):
            w(f"    • {mot:<60}  {cnt:>6,}")
        dest_fail = defaultdict(int)
        for r in resultados_fail: dest_fail[r["envio"]["destino"]] += 1
        w("")
        w("  Destinos afectados:")
        for d, c in sorted(dest_fail.items(), key=lambda x: -x[1]):
            w(f"    • {d}  ({c:,} envíos)  —  En BD: {'Sí' if d in aeropuertos else 'NO'}")
        w("")
        w("  Detalle:")
        w("")
        for i, res in enumerate(resultados_fail, 1):
            env  = res["envio"]
            tz_o = aeropuertos.get(env["origen"], {}).get("tz_offset", 0)
            w(f"  [{i:>6}]  Envío {env['id_envio']}  ·  Cliente {env['id_cliente']}")
            w(f"          {env['origen']}(UTC{tz_o:+d}) → {env['destino']}"
              f"  |  {env['hora_local'].strftime('%Y-%m-%d %H:%M')} local"
              f" / {env['hora_utc'].strftime('%H:%M')} UTC"
              f"  |  {env['cantidad']} maleta(s)")
            w(f"          Motivo: {res['motivo']}")
            if i < n_fail: w("")
    w("")
    w(SM)

    # SECCIÓN 3 ────────────────────────────────────────────────────────────
    if errores_formato:
        w("")
        w(f"  SECCIÓN 3 — ERRORES DE FORMATO ({n_err:,} líneas)")
        w(Sm)
        w("")
        for num_l, raw, mot in errores_formato:
            w(f"  Línea {num_l:>8}: {raw}")
            w(f"             Motivo: {mot}")
            w("")
        w(SM)

    w("")
    w(f"  Fin del reporte  ·  {timestamp}")
    w(SM)

    contenido = "\n".join(L)
    with open(path_salida, "w", encoding="utf-8") as f:
        f.write(contenido)
    return contenido


def generar_reporte_csv(resultados_ok, resultados_fail, path_salida_csv):
    import csv
    with open(path_salida_csv, "w", newline="", encoding="utf-8") as f:
        wr = csv.writer(f)
        wr.writerow([
            "id_envio", "id_cliente", "origen", "destino",
            "hora_local", "hora_utc", "cantidad", "estado",
            "costo_horas", "dias_viaje", "escalas",
            "cumple_plazo", "plazo_dias", "ruta", "num_vuelos", "motivo_fallo",
        ])
        for res in resultados_ok:
            env = res["envio"]
            wr.writerow([
                env["id_envio"], env["id_cliente"], env["origen"], env["destino"],
                env["hora_local"].strftime("%Y-%m-%d %H:%M"),
                env["hora_utc"].strftime("%Y-%m-%d %H:%M"),
                env["cantidad"], "OK",
                f"{res['costo_horas']:.2f}", f"{res['dias_viaje']:.4f}",
                res["escalas"], res["cumple_plazo"], res["plazo_dias"],
                " > ".join(res["ruta"]), len(res["vuelos_usados"]), "",
            ])
        for res in resultados_fail:
            env = res["envio"]
            wr.writerow([
                env["id_envio"], env["id_cliente"], env["origen"], env["destino"],
                env["hora_local"].strftime("%Y-%m-%d %H:%M"),
                env["hora_utc"].strftime("%Y-%m-%d %H:%M"),
                env["cantidad"], "FALLIDO",
                "", "", "", "", "", "", "", res["motivo"],
            ])


# ══════════════════════════════════════════════════════════════════════════════
# MAIN
# ══════════════════════════════════════════════════════════════════════════════

def main():
    parser = argparse.ArgumentParser(
        description="Tasf.B2B — Planificador de rutas de maletas (A*)"
    )
    parser.add_argument("--aeropuertos", default="aeropuertos.txt")
    parser.add_argument("--vuelos",      default="vuelos.txt")
    parser.add_argument("--envios",      required=True, nargs="+")
    parser.add_argument("--salida",      default="reporte_planificacion")
    parser.add_argument("--workers",     type=int, default=4)
    parser.add_argument("--limite",      type=int, default=None)
    parser.add_argument("--sin-progreso",action="store_true")
    args = parser.parse_args()

    print("═" * 70)
    print("  TASF.B2B — Planificador de rutas  (A*)  v2  [FIX-1/2/3]")
    print("═" * 70)

    print(f"\n[1/4] Cargando aeropuertos desde '{args.aeropuertos}' …")
    aeropuertos, cap_base = leer_aeropuertos(args.aeropuertos)
    tz_muestra = "  ".join(f"{k}:UTC{v['tz_offset']:+d}" for k, v in list(aeropuertos.items())[:5])
    print(f"      {len(aeropuertos)} aeropuertos  |  {tz_muestra} …")

    print(f"\n[2/4] Cargando vuelos desde '{args.vuelos}' …")
    vuelos = leer_vuelos(args.vuelos, aeropuertos)
    print(f"      {len(vuelos):,} vuelos  (horarios local→UTC aplicados)")

    todos_ok, todos_fail, todos_err = [], [], []
    origenes = []

    print(f"\n[3/4] Procesando {len(args.envios)} archivo(s) de envíos …")
    for path_e in args.envios:
        print(f"\n  ► {path_e}")
        envios, origen, errs = leer_envios(path_e, aeropuertos)
        origenes.append(origen)
        print(f"    {len(envios):,} envíos  |  {len(errs)} errores de formato")
        if args.limite:
            envios = envios[:args.limite]
            print(f"    (limitado a {args.limite})")
        if not envios:
            continue

        t0 = time.time()
        ok, fail = planificar_lote(
            envios, vuelos, aeropuertos, cap_base,
            max_workers=args.workers,
            mostrar_progreso=not args.sin_progreso,
        )
        print(f"\n    Completado en {_fmt_tiempo(time.time()-t0)}"
              f"  |  OK: {len(ok):,}  |  FAIL: {len(fail):,}")
        todos_ok.extend(ok); todos_fail.extend(fail); todos_err.extend(errs)

    print(f"\n[4/4] Generando reporte …")
    label    = "+".join(origenes) or "MULTI"
    path_txt = f"{args.salida}.txt"
    path_csv = f"{args.salida}.csv"
    generar_reporte(todos_ok, todos_fail, todos_err, label, path_txt, vuelos, aeropuertos)
    generar_reporte_csv(todos_ok, todos_fail, path_csv)
    print(f"  {path_txt}")
    print(f"  {path_csv}")

    total = len(todos_ok) + len(todos_fail)
    print(f"\n{'═'*70}")
    print(f"  RESUMEN FINAL")
    print(f"{'─'*70}")
    if total:
        print(f"  Total        : {total:,}")
        print(f"  OK           : {len(todos_ok):,}  ({len(todos_ok)/total*100:.1f}%)")
        print(f"  FALLIDOS     : {len(todos_fail):,}  ({len(todos_fail)/total*100:.1f}%)")
    if todos_ok:
        n_c = sum(1 for r in todos_ok if r["cumple_plazo"])
        print(f"  Cumplen plazo: {n_c:,}  ({n_c/len(todos_ok)*100:.1f}% de OK)")
    print(f"{'═'*70}\n")


# ══════════════════════════════════════════════════════════════════════════════
# MODO DIRECTO  (sin argumentos → demo con 20 envíos)
# ══════════════════════════════════════════════════════════════════════════════

if __name__ == "__main__":
    if len(sys.argv) == 1:
        print("Uso:")
        print("  python planificacion_maleta.py \\")
        print("    --aeropuertos aeropuertos.txt \\")
        print("    --vuelos      vuelos.txt \\")
        print("    --envios      _envios_SBBR_.txt \\")
        print("    --salida      reporte_planificacion \\")
        print("    --workers     4  [--limite 100]\n")
        print("Ejecutando demo (1000 envíos) …\n")

        aeropuertos, cap_base = leer_aeropuertos("aeropuertos.txt")
        vuelos  = leer_vuelos("vuelos.txt", aeropuertos)
        envios, origen, errs = leer_envios("_envios_SBBR_.txt", aeropuertos)

        t0 = time.time()
        ok, fail = planificar_lote(envios[:1000], vuelos, aeropuertos, cap_base,
                                   max_workers=2, mostrar_progreso=True)
        print(f"\nCompletado en {_fmt_tiempo(time.time()-t0)}"
              f"  |  OK: {len(ok)}  |  FAIL: {len(fail)}")
        generar_reporte(ok, fail, errs, origen, "reporte_demo.txt", vuelos, aeropuertos)
        generar_reporte_csv(ok, fail, "reporte_demo.csv")
        print("Reportes: reporte_demo.txt  /  reporte_demo.csv")
    else:
        main()
