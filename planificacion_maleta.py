import heapq
from datetime import datetime, timedelta
import math
import re

# -----------------------------
# 🌍 Haversine + Heurística
# -----------------------------
def haversine(lat1, lon1, lat2, lon2):
    R = 6371
    phi1, phi2 = math.radians(lat1), math.radians(lat2)
    dphi = math.radians(lat2 - lat1)
    dlambda = math.radians(lon2 - lon1)

    a = math.sin(dphi/2)**2 + math.cos(phi1)*math.cos(phi2)*math.sin(dlambda/2)**2
    return 2 * R * math.atan2(math.sqrt(a), math.sqrt(1-a))

def heuristic(a, b, aeropuertos):
    lat1, lon1 = aeropuertos[a]["lat"], aeropuertos[a]["lon"]
    lat2, lon2 = aeropuertos[b]["lat"], aeropuertos[b]["lon"]

    return haversine(lat1, lon1, lat2, lon2) / 800

# -----------------------------
# 📦 Capacidad aeropuerto
# -----------------------------
def hay_capacidad(aeropuerto, tiempo_llegada, capacidad_aeropuerto):
    ventana = timedelta(hours=1)
    ocupados = 0

    for t in capacidad_aeropuerto[aeropuerto]["ocupacion"]:
        if abs(t - tiempo_llegada) <= ventana:
            ocupados += 1

    return ocupados < capacidad_aeropuerto[aeropuerto]["max"]

def registrar_ocupacion(aeropuerto, tiempo, capacidad_aeropuerto):
    capacidad_aeropuerto[aeropuerto]["ocupacion"].append(tiempo)

# -----------------------------
# 📍 Parseo coordenadas
# -----------------------------
def dms_a_decimal(grados, minutos, segundos, direccion):
    decimal = grados + minutos/60 + segundos/3600
    if direccion in ["S", "W"]:
        decimal *= -1
    return decimal

def parsear_coordenadas(linea):
    lat_match = re.search(r"Latitude:\s*(\d+)°\s*(\d+)'\s*(\d+)\"?\s*([NS])", linea)
    lon_match = re.search(r"Longitude:\s*(\d+)°\s*(\d+)'\s*(\d+)\"?\s*([EW])", linea)

    if not lat_match or not lon_match:
        print("[WARNING] Linea con formato invalido:", linea)
        return None, None

    lat = dms_a_decimal(*map(int, lat_match.groups()[:3]), lat_match.group(4))
    lon = dms_a_decimal(*map(int, lon_match.groups()[:3]), lon_match.group(4))

    return lat, lon

# -----------------------------
# 🏢 Leer aeropuertos
# -----------------------------
def leer_aeropuertos(path):
    aeropuertos = {}
    capacidad = {}

    with open(path, "r", encoding="utf-16") as f:
        for linea in f:
            partes = linea.split()

            # 📍 Código ICAO (siempre en posición 1)
            codigo = partes[1]

            # 📦 Capacidad (siempre antes de "Latitude")
            for i, p in enumerate(partes):
                if p.startswith("Latitude"):
                    capacidad_max = int(partes[i - 1])
                    break

            # 🌍 Coordenadas
            lat, lon = parsear_coordenadas(linea)

            if lat is None:
                continue  # saltar líneas malas

            aeropuertos[codigo] = {
                "lat": lat,
                "lon": lon
            }

            capacidad[codigo] = {
                "max": capacidad_max,
                "ocupacion": []
            }

    return aeropuertos, capacidad

# -----------------------------
# ✈️ Leer vuelos
# -----------------------------
def leer_vuelos(path):
    vuelos = []

    with open(path, "r", encoding="utf-8") as f:
        for linea in f:
            linea = linea.strip()

            # SKBO-SEQM-03:34-04:21-0300
            origen, destino, h1, h2, cap = re.split(r"[-]", linea)

            salida = datetime.strptime(h1, "%H:%M")
            llegada = datetime.strptime(h2, "%H:%M")

            vuelos.append({
                "origen": origen,
                "destino": destino,
                "salida": salida,
                "llegada": llegada,
                "capacidad": int(cap)
            })

    return vuelos

# -----------------------------
# ✈️ Nodo A*
# -----------------------------
# class Nodo:
    def __init__(self, aeropuerto, tiempo, costo, padre=None, vuelo=None):
        self.aeropuerto = aeropuerto
        self.tiempo = tiempo
        self.costo = costo
        self.padre = padre
        self.vuelo = vuelo  # ✈️ vuelo usado para llegar aquí
# -----------------------------
# 🚀 A*
# -----------------------------
def a_star(origen, destino, vuelos, aeropuertos, capacidad_aeropuerto, tiempo_inicio):
    
    class Nodo:
        def __init__(self, aeropuerto, tiempo, costo, padre=None, vuelo=None):
            self.aeropuerto = aeropuerto
            self.tiempo = tiempo
            self.costo = costo
            self.padre = padre
            self.vuelo = vuelo

        def __lt__(self, other):
            return self.costo < other.costo

    def reconstruir_camino(nodo):
        ruta = []
        vuelos_usados = []

        while nodo:
            ruta.append(nodo.aeropuerto)
            if nodo.vuelo:
                vuelos_usados.append(nodo.vuelo)
            nodo = nodo.padre

        return list(reversed(ruta)), list(reversed(vuelos_usados))

    abiertos = []
    heapq.heappush(abiertos, (0, Nodo(origen, tiempo_inicio, 0)))

    visitados = {}

    while abiertos:
        _, actual = heapq.heappop(abiertos)

        # 🎯 objetivo alcanzado
        if actual.aeropuerto == destino:
            ruta, vuelos_usados = reconstruir_camino(actual)
            return ruta, vuelos_usados, actual.costo

        clave = (actual.aeropuerto, actual.tiempo)
        if clave in visitados and visitados[clave] <= actual.costo:
            continue
        visitados[clave] = actual.costo

        for vuelo in vuelos:
            if vuelo["origen"] != actual.aeropuerto:
                continue

            # 🧠 construir datetime real
            salida = vuelo["salida"].replace(
                year=actual.tiempo.year,
                month=actual.tiempo.month,
                day=actual.tiempo.day
            )

            llegada = vuelo["llegada"].replace(
                year=actual.tiempo.year,
                month=actual.tiempo.month,
                day=actual.tiempo.day
            )

            # 🌙 vuelo cruza medianoche
            if llegada < salida:
                llegada += timedelta(days=1)

            # ⏭️ vuelo ya salió → siguiente día
            if salida < actual.tiempo:
                salida += timedelta(days=1)
                llegada += timedelta(days=1)

            # ⏱️ costos
            espera = (salida - actual.tiempo).total_seconds() / 3600
            duracion = (llegada - salida).total_seconds() / 3600

            if espera < 0 or duracion < 0:
                continue  # seguridad extra

            nuevo_costo = actual.costo + espera + duracion

            # 🏢 validar aeropuerto destino
            if vuelo["destino"] not in capacidad_aeropuerto:
                continue

            # 📦 validar capacidad
            if not hay_capacidad(vuelo["destino"], llegada, capacidad_aeropuerto):
                continue

            # 📊 registrar ocupación
            registrar_ocupacion(vuelo["destino"], llegada, capacidad_aeropuerto)

            # 🔮 heurística
            h = heuristic(vuelo["destino"], destino, aeropuertos)

            nuevo_nodo = Nodo(
                vuelo["destino"],
                llegada,
                nuevo_costo,
                actual,
                vuelo
            )

            heapq.heappush(abiertos, (nuevo_costo + h, nuevo_nodo))

    return None, None, float("inf")
# -----------------------------
# ▶️ MAIN
# -----------------------------
if __name__ == "__main__":
    aeropuertos, capacidad = leer_aeropuertos("aeropuertos.txt")
    vuelos = leer_vuelos("vuelos.txt")

    ruta, vuelos, costo = a_star(
        "SKBO",
        "SGAS",
        vuelos,
        aeropuertos,
        capacidad,
        datetime(2024, 1, 1, 0, 0)
    )

    print("Ruta:", ruta)
    print("Costo:", costo)

    print("\nVuelos:")
    for v in vuelos:
        print(f"{v['origen']} -> {v['destino']} | {v['salida'].time()} - {v['llegada'].time()}")