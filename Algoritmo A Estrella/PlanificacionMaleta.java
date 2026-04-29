import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.time.format.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.regex.*;
import java.util.stream.*;

/**
 * Tasf.B2B — Planificador de Rutas de Maletas  (A*)  — Java
 * ===========================================================
 *
 * Uso:
 *   java PlanificacionMaleta \
 *       --aeropuertos aeropuertos.txt \
 *       --vuelos      vuelos.txt \
 *       --directorio  ./envios \
 *       --fecha-ini   2026-01-02 \
 *       --dias        5 \
 *       --salida      reporte_planificacion \
 *       --workers     4 \
 *       [--limite     100]
 *
 * Nuevos parámetros respecto a la versión Python:
 *   --fecha-ini   Fecha de inicio de la simulación (YYYY-MM-DD).
 *   --dias        Número de días a simular (ej. 3, 5, 7).
 *                 Solo se procesan envíos con hora_local ∈ [fecha-ini, fecha-ini + dias).
 *   --directorio  Carpeta donde se buscan automáticamente los archivos
 *                 _envios_XXXX_.txt para cada aeropuerto cargado.
 *                 Si falta el archivo de algún aeropuerto → WARNING, continúa.
 *
 * Correcciones heredadas del Python:
 *   [FIX-1] Zonas horarias: horarios en hora LOCAL → convertidos a UTC.
 *   [FIX-2] Capacidad de vuelo compartida (thread-safe) entre todos los envíos.
 *   [FIX-3] Un vuelo puede transportar múltiples envíos hasta su cap. máxima.
 */
public class PlanificacionMaleta {

    // ═══════════════════════════════════════════════════════════════════════
    // CONSTANTES
    // ═══════════════════════════════════════════════════════════════════════

    private static final double EARTH_RADIUS_KM   = 6371.0;
    private static final double REF_SPEED_KMH     = 900.0;
    private static final long   VENTANA_ALMACEN_H = 1;      // ±1 h para almacén
    private static final String SEP_MAYOR = "═".repeat(100);
    private static final String SEP_MENOR = "─".repeat(100);
    private static final String SEP_SECC  = "·".repeat(100);
    private static final DateTimeFormatter FMT_DATETIME =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final DateTimeFormatter FMT_DATE =
        DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter FMT_TIME =
        DateTimeFormatter.ofPattern("HH:mm");

    // ═══════════════════════════════════════════════════════════════════════
    // MODELOS DE DATOS
    // ═══════════════════════════════════════════════════════════════════════

    /** Información de un aeropuerto leída de aeropuertos.txt. */
    static class Aeropuerto {
        final String icao;
        final double lat, lon;
        final int    tzOffset;   // offset UTC con signo (ej. -5 para Bogotá)
        final int    capMax;     // capacidad máxima del almacén

        Aeropuerto(String icao, double lat, double lon, int tzOffset, int capMax) {
            this.icao     = icao;
            this.lat      = lat;
            this.lon      = lon;
            this.tzOffset = tzOffset;
            this.capMax   = capMax;
        }
    }

    /** Estado mutable del almacén de un aeropuerto (copia por envío). */
    static class EstadoAlmacen {
        final int capMax;
        // Lista de (tiempo_utc_epoch_segundos, cantidad)
        final List<long[]> ocupacion = new ArrayList<>();

        EstadoAlmacen(int capMax) { this.capMax = capMax; }

        EstadoAlmacen clonar() {
            EstadoAlmacen c = new EstadoAlmacen(capMax);
            c.ocupacion.addAll(ocupacion);
            return c;
        }
    }

    /** Plan de vuelo leído de vuelos.txt. */
    static class Vuelo {
        final String origen, destino;
        final LocalTime salidaLocal;    // hora local del aeropuerto ORIGEN
        final LocalTime llegadaLocal;   // hora local del aeropuerto DESTINO
        final LocalTime salidaUtc;      // [FIX-1] convertida a UTC
        final LocalTime llegadaUtc;     // [FIX-1] convertida a UTC
        final int       capacidad;
        final int       tzOrig, tzDest;
        // Clave única de slot: "ORIG-DEST-HH:MM"
        final String    clave;

        Vuelo(String origen, String destino,
              LocalTime salidaLocal, LocalTime llegadaLocal,
              LocalTime salidaUtc,   LocalTime llegadaUtc,
              int capacidad, int tzOrig, int tzDest) {
            this.origen       = origen;
            this.destino      = destino;
            this.salidaLocal  = salidaLocal;
            this.llegadaLocal = llegadaLocal;
            this.salidaUtc    = salidaUtc;
            this.llegadaUtc   = llegadaUtc;
            this.capacidad    = capacidad;
            this.tzOrig       = tzOrig;
            this.tzDest       = tzDest;
            this.clave        = origen + "-" + destino + "-" + FMT_TIME.format(salidaLocal);
        }
    }

    /** Envío de maletas leído de _envios_ORIG_.txt. */
    static class Envio {
        final String        idEnvio, origen, destino, idCliente;
        final LocalDateTime horaLocal;   // hora recepción en hora LOCAL del origen
        final LocalDateTime horaUtc;     // [FIX-1] convertida a UTC
        final int           cantidad;
        final int           numLinea;

        Envio(String idEnvio, String origen, String destino, String idCliente,
              LocalDateTime horaLocal, LocalDateTime horaUtc, int cantidad, int numLinea) {
            this.idEnvio   = idEnvio;
            this.origen    = origen;
            this.destino   = destino;
            this.idCliente = idCliente;
            this.horaLocal = horaLocal;
            this.horaUtc   = horaUtc;
            this.cantidad  = cantidad;
            this.numLinea  = numLinea;
        }
    }

    /** Resultado exitoso de planificar un envío. */
    static class ResultadoOk {
        final Envio        envio;
        final List<String> ruta;
        final List<Vuelo>  vuelos;
        final double       costoHoras;
        final boolean      cumplePlazo;
        final int          plazoDias;
        final int          escalas;
        final int[]        ocupacionVuelos;  // ocupación al momento de asignar (informativo)

        ResultadoOk(Envio envio, List<String> ruta, List<Vuelo> vuelos,
                    double costoHoras, boolean cumplePlazo, int plazoDias,
                    int escalas, int[] ocupacionVuelos) {
            this.envio           = envio;
            this.ruta            = ruta;
            this.vuelos          = vuelos;
            this.costoHoras      = costoHoras;
            this.cumplePlazo     = cumplePlazo;
            this.plazoDias       = plazoDias;
            this.escalas         = escalas;
            this.ocupacionVuelos = ocupacionVuelos;
        }
    }

    /** Resultado fallido de planificar un envío. */
    static class ResultadoFail {
        final Envio  envio;
        final String motivo;

        ResultadoFail(Envio envio, String motivo) {
            this.envio  = envio;
            this.motivo = motivo;
        }
    }

    /** Error de formato en un archivo de envíos. */
    static class ErrorFormato {
        final int    numLinea;
        final String raw, motivo;

        ErrorFormato(int numLinea, String raw, String motivo) {
            this.numLinea = numLinea;
            this.raw      = raw;
            this.motivo   = motivo;
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // [FIX-2 + FIX-3]  ESTADO COMPARTIDO DE CAPACIDAD DE VUELOS
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Registra cuántas maletas lleva cada vuelo.
     * Compartido entre todos los hilos; acceso protegido con ConcurrentHashMap
     * y operaciones CAS para garantizar thread-safety sin lock explícito global.
     */
    static class EstadoVuelos {
        // clave_vuelo → [ocupado, cap_max]
        private final ConcurrentHashMap<String, int[]> estado = new ConcurrentHashMap<>();

        EstadoVuelos(List<Vuelo> vuelos) {
            for (Vuelo v : vuelos) {
                estado.putIfAbsent(v.clave, new int[]{0, v.capacidad});
            }
        }

        boolean hayCapacidad(Vuelo v, int cantidad) {
            int[] e = estado.get(v.clave);
            return e != null && (e[0] + cantidad) <= e[1];
        }

        /** Reserva atómica usando synchronized en la entrada del mapa. */
        boolean reservar(Vuelo v, int cantidad) {
            int[] e = estado.get(v.clave);
            if (e == null) return false;
            synchronized (e) {
                if ((e[0] + cantidad) > e[1]) return false;
                e[0] += cantidad;
                return true;
            }
        }

        /** Rollback: devuelve maletas al vuelo. */
        void liberar(Vuelo v, int cantidad) {
            int[] e = estado.get(v.clave);
            if (e != null) {
                synchronized (e) {
                    e[0] = Math.max(0, e[0] - cantidad);
                }
            }
        }

        int[] ocupacion(Vuelo v) {
            int[] e = estado.get(v.clave);
            return e != null ? new int[]{e[0], e[1]} : new int[]{0, 0};
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // GEOGRAFÍA: HAVERSINE + HEURÍSTICA
    // ═══════════════════════════════════════════════════════════════════════

    static double haversine(double lat1, double lon1, double lat2, double lon2) {
        double phi1 = Math.toRadians(lat1), phi2 = Math.toRadians(lat2);
        double dphi    = Math.toRadians(lat2 - lat1);
        double dlambda = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dphi / 2) * Math.sin(dphi / 2)
                 + Math.cos(phi1) * Math.cos(phi2)
                 * Math.sin(dlambda / 2) * Math.sin(dlambda / 2);
        return 2 * EARTH_RADIUS_KM * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }

    static double heuristica(String a, String b, Map<String, Aeropuerto> aeropuertos) {
        Aeropuerto ap = aeropuertos.get(a), bp = aeropuertos.get(b);
        if (ap == null || bp == null) return 0.0;
        return haversine(ap.lat, ap.lon, bp.lat, bp.lon) / REF_SPEED_KMH;
    }

    // ═══════════════════════════════════════════════════════════════════════
    // [FIX-1]  CONVERSIÓN LOCAL ↔ UTC
    // UTC = hora_local − offset_horas
    // ═══════════════════════════════════════════════════════════════════════

    static LocalDateTime localAUtc(LocalDateTime local, int offsetHoras) {
        return local.minusHours(offsetHoras);
    }

    static LocalTime localTimeAUtc(LocalTime local, int offsetHoras) {
        return local.minusHours(offsetHoras);
    }

    // ═══════════════════════════════════════════════════════════════════════
    // CAPACIDAD DE ALMACÉN
    // ═══════════════════════════════════════════════════════════════════════

    static boolean hayCapacidadAlmacen(EstadoAlmacen ea,
                                       LocalDateTime tUtc, int cantidad) {
        long tEpoch = tUtc.toEpochSecond(ZoneOffset.UTC);
        long ventana = VENTANA_ALMACEN_H * 3600;
        int ocupados = 0;
        for (long[] entrada : ea.ocupacion) {
            if (Math.abs(entrada[0] - tEpoch) <= ventana)
                ocupados += (int) entrada[1];
        }
        return (ocupados + cantidad) <= ea.capMax;
    }

    static void registrarOcupacion(EstadoAlmacen ea,
                                   LocalDateTime tUtc, int cantidad) {
        ea.ocupacion.add(new long[]{tUtc.toEpochSecond(ZoneOffset.UTC), cantidad});
    }

    // ═══════════════════════════════════════════════════════════════════════
    // PARSEO DE COORDENADAS GMS → DECIMAL
    // ═══════════════════════════════════════════════════════════════════════

    private static final Pattern PAT_LAT =
        Pattern.compile("Latitude:\\s*(\\d+)°\\s*(\\d+)'\\s*(\\d+)\"?\\s*([NS])");
    private static final Pattern PAT_LON =
        Pattern.compile("Longitude:\\s*(\\d+)°\\s*(\\d+)'\\s*(\\d+)\"?\\s*([EW])");

    static double dmsADecimal(int g, int m, int s, String dir) {
        double d = g + m / 60.0 + s / 3600.0;
        return (dir.equals("S") || dir.equals("W")) ? -d : d;
    }

    /** Devuelve [lat, lon] o null si la línea no tiene coordenadas válidas. */
    static double[] parsearCoordenadas(String linea) {
        Matcher ml = PAT_LAT.matcher(linea);
        Matcher mo = PAT_LON.matcher(linea);
        if (!ml.find() || !mo.find()) return null;
        double lat = dmsADecimal(
            Integer.parseInt(ml.group(1)), Integer.parseInt(ml.group(2)),
            Integer.parseInt(ml.group(3)), ml.group(4));
        double lon = dmsADecimal(
            Integer.parseInt(mo.group(1)), Integer.parseInt(mo.group(2)),
            Integer.parseInt(mo.group(3)), mo.group(4));
        return new double[]{lat, lon};
    }

    // ═══════════════════════════════════════════════════════════════════════
    // LEER AEROPUERTOS
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Lee aeropuertos.txt (UTF-16).
     * Extrae: ICAO, lat, lon, tz_offset (campo antes de cap), cap_max.
     */
    static Map<String, Aeropuerto> leerAeropuertos(String path) throws IOException {
        Map<String, Aeropuerto> mapa = new LinkedHashMap<>();
        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(new FileInputStream(path),
                                      StandardCharsets.UTF_16))) {
            String linea;
            while ((linea = br.readLine()) != null) {
                String[] partes = linea.trim().split("\\s+");
                if (partes.length < 3) continue;
                String icao = partes[1];
                // Encontrar índice del token que empieza con "Latitude"
                int latIdx = -1;
                for (int i = 0; i < partes.length; i++) {
                    if (partes[i].startsWith("Latitude")) { latIdx = i; break; }
                }
                if (latIdx < 2) continue;
                int capMax, tzOffset;
                try {
                    capMax   = Integer.parseInt(partes[latIdx - 1]);
                    tzOffset = Integer.parseInt(partes[latIdx - 2]);
                } catch (NumberFormatException e) { continue; }
                double[] coords = parsearCoordenadas(linea);
                if (coords == null) continue;
                mapa.put(icao, new Aeropuerto(icao, coords[0], coords[1], tzOffset, capMax));
            }
        }
        return mapa;
    }

    // ═══════════════════════════════════════════════════════════════════════
    // LEER VUELOS  [FIX-1]
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Formato: ORIG-DEST-HO:MO-HD:MD-CAP
     * HO:MO = hora salida LOCAL del aeropuerto ORIGEN
     * HD:MD = hora llegada LOCAL del aeropuerto DESTINO
     * [FIX-1] Ambas se convierten a UTC usando el offset del aeropuerto.
     */
    static List<Vuelo> leerVuelos(String path,
                                   Map<String, Aeropuerto> aeropuertos) throws IOException {
        List<Vuelo> lista = new ArrayList<>();
        try (BufferedReader br = Files.newBufferedReader(Paths.get(path),
                                                        StandardCharsets.UTF_8)) {
            String linea;
            while ((linea = br.readLine()) != null) {
                linea = linea.trim();
                if (linea.isEmpty()) continue;
                String[] p = linea.split("-");
                if (p.length != 5) continue;
                try {
                    String    origen  = p[0], destino = p[1];
                    LocalTime salLoc  = LocalTime.parse(p[2], FMT_TIME);
                    LocalTime llegLoc = LocalTime.parse(p[3], FMT_TIME);
                    int       cap     = Integer.parseInt(p[4]);
                    int tzO = aeropuertos.containsKey(origen)  ? aeropuertos.get(origen).tzOffset  : 0;
                    int tzD = aeropuertos.containsKey(destino) ? aeropuertos.get(destino).tzOffset : 0;
                    // [FIX-1] convertir a UTC
                    LocalTime salUtc  = localTimeAUtc(salLoc,  tzO);
                    LocalTime llegUtc = localTimeAUtc(llegLoc, tzD);
                    lista.add(new Vuelo(origen, destino, salLoc, llegLoc,
                                       salUtc, llegUtc, cap, tzO, tzD));
                } catch (Exception ignored) {}
            }
        }
        return lista;
    }

    // ═══════════════════════════════════════════════════════════════════════
    // LEER ENVÍOS POR AEROPUERTO  [FIX-1] + FILTRO DE FECHAS
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Resultado de leer un archivo de envíos.
     */
    static class LecturaEnvios {
        final List<Envio>         envios          = new ArrayList<>();
        final List<ErrorFormato>  erroresFormato  = new ArrayList<>();
        final String              origen;
        int totalLineas = 0, fueraRango = 0;

        LecturaEnvios(String origen) { this.origen = origen; }
    }

    /**
     * Lee un archivo _envios_ORIG_.txt filtrando por rango de fechas.
     * Formato línea: id_envio-aaaammdd-hh-mm-dest-cant-IdCliente
     * hh-mm = hora LOCAL del aeropuerto origen.
     * [FIX-1] Convierte a UTC para comparación interna.
     *
     * @param path         Ruta al archivo.
     * @param aeropuertos  Mapa de aeropuertos (para obtener tz_offset).
     * @param fechaIni     Fecha de inicio del rango (inclusive), en hora LOCAL del origen.
     * @param diasSim      Número de días de simulación.
     */
    static LecturaEnvios leerEnvios(String path,
                                     Map<String, Aeropuerto> aeropuertos,
                                     LocalDate fechaIni,
                                     int diasSim) throws IOException {
        // Inferir ICAO origen del nombre del archivo: _envios_SBBR_.txt → SBBR
        String nombre = Paths.get(path).getFileName().toString();
        Matcher m = Pattern.compile("_envios_([A-Z0-9]{3,4})_",
                                    Pattern.CASE_INSENSITIVE).matcher(nombre);
        String origen = m.find() ? m.group(1).toUpperCase() : "????";

        LecturaEnvios resultado = new LecturaEnvios(origen);
        int tzOrigen = aeropuertos.containsKey(origen)
                       ? aeropuertos.get(origen).tzOffset : 0;

        // Rango en hora LOCAL: [fechaIni 00:00, fechaIni + diasSim 00:00)
        LocalDateTime inicioLocal = fechaIni.atStartOfDay();
        LocalDateTime finLocal    = fechaIni.plusDays(diasSim).atStartOfDay();

        try (BufferedReader br = Files.newBufferedReader(Paths.get(path),
                                                        StandardCharsets.UTF_8)) {
            String linea;
            int numLinea = 0;
            while ((linea = br.readLine()) != null) {
                numLinea++;
                // Limpiar BOM y \r
                linea = linea.replace("\uFEFF", "").replace("\r", "").trim();
                if (linea.isEmpty()) continue;
                resultado.totalLineas++;

                String[] partes = linea.split("-");
                if (partes.length != 7) {
                    resultado.erroresFormato.add(new ErrorFormato(numLinea, linea,
                        "Número de campos incorrecto (" + partes.length + " en lugar de 7)"));
                    continue;
                }
                String idEnvio = partes[0], fechaStr = partes[1],
                       hh = partes[2], mm = partes[3], dest = partes[4],
                       cantStr = partes[5], idCliente = partes[6];
                try {
                    LocalDate   fecha      = LocalDate.parse(fechaStr,
                                                DateTimeFormatter.ofPattern("yyyyMMdd"));
                    LocalDateTime horaLoc  = LocalDateTime.of(fecha,
                                                LocalTime.of(Integer.parseInt(hh),
                                                             Integer.parseInt(mm)));
                    // ── FILTRO DE RANGO ──────────────────────────────────
                    if (horaLoc.isBefore(inicioLocal) || !horaLoc.isBefore(finLocal)) {
                        resultado.fueraRango++;
                        continue;
                    }
                    LocalDateTime horaUtc = localAUtc(horaLoc, tzOrigen); // [FIX-1]
                    int cantidad = Integer.parseInt(cantStr);
                    resultado.envios.add(new Envio(idEnvio, origen, dest, idCliente,
                                                   horaLoc, horaUtc, cantidad, numLinea));
                } catch (Exception e) {
                    resultado.erroresFormato.add(new ErrorFormato(numLinea, linea,
                        "Error de parseo: " + e.getMessage()));
                }
            }
        }
        return resultado;
    }

    /**
     * Descubre y lee automáticamente los archivos _envios_XXXX_.txt para cada
     * aeropuerto cargado, buscando en {@code directorioEnvios}.
     * Si el archivo de un aeropuerto no existe → WARNING, continúa.
     *
     * @param directorioEnvios  Carpeta donde se buscan los archivos.
     * @param aeropuertos       Mapa de aeropuertos para iterar sus ICAO.
     * @param fechaIni          Fecha inicio de simulación.
     * @param diasSim           Días de simulación.
     * @return Lista de LecturaEnvios (una por archivo encontrado).
     */
    static List<LecturaEnvios> leerEnviosPorAeropuerto(
            String directorioEnvios,
            Map<String, Aeropuerto> aeropuertos,
            LocalDate fechaIni,
            int diasSim) throws IOException {

        List<LecturaEnvios> resultados = new ArrayList<>();
        int encontrados = 0, faltantes = 0;

        for (String icao : aeropuertos.keySet()) {
            // Patrón esperado: _envios_SBBR_.txt  (case-insensitive)
            String nombreArchivo = "_envios_" + icao + "_.txt";
            Path ruta = Paths.get(directorioEnvios, nombreArchivo);

            if (!Files.exists(ruta)) {
                // Intentar también en minúsculas
                ruta = Paths.get(directorioEnvios, nombreArchivo.toLowerCase());
            }
            if (!Files.exists(ruta)) {
                System.out.printf("  [WARNING] No se encontró archivo de envíos para %s " +
                                  "(esperado: %s)%n", icao, nombreArchivo);
                faltantes++;
                continue;
            }

            System.out.printf("  ► Leyendo %-40s ...", ruta.getFileName());
            LecturaEnvios lr = leerEnvios(ruta.toString(), aeropuertos, fechaIni, diasSim);
            System.out.printf(" %,7d envíos en rango | %,7d fuera de rango | %d errores%n",
                lr.envios.size(), lr.fueraRango, lr.erroresFormato.size());
            resultados.add(lr);
            encontrados++;
        }

        System.out.printf("%n  Archivos encontrados: %d | Aeropuertos sin archivo: %d%n",
                          encontrados, faltantes);
        return resultados;
    }

    // ═══════════════════════════════════════════════════════════════════════
    // NODO DEL A*
    // ═══════════════════════════════════════════════════════════════════════

    static class Nodo implements Comparable<Nodo> {
        final String        aeropuerto;
        final LocalDateTime tiempoUtc;
        final double        costo;
        final Nodo          padre;
        final Vuelo         vuelo;      // vuelo usado para llegar aquí (null en raíz)

        Nodo(String aeropuerto, LocalDateTime tiempoUtc, double costo,
             Nodo padre, Vuelo vuelo) {
            this.aeropuerto = aeropuerto;
            this.tiempoUtc  = tiempoUtc;
            this.costo      = costo;
            this.padre      = padre;
            this.vuelo      = vuelo;
        }

        @Override public int compareTo(Nodo o) {
            return Double.compare(this.costo, o.costo);
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // ALGORITMO A*
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Resultado del A*: ruta, vuelos usados, costo y lista de reservas
     * (para rollback en caso de fallo posterior).
     */
    static class ResultadoAstar {
        final List<String> ruta;
        final List<Vuelo>  vuelosUsados;
        final double       costoHoras;
        final List<Vuelo>  reservasHechas;  // para rollback

        ResultadoAstar(List<String> ruta, List<Vuelo> vuelosUsados,
                       double costoHoras, List<Vuelo> reservasHechas) {
            this.ruta           = ruta;
            this.vuelosUsados   = vuelosUsados;
            this.costoHoras     = costoHoras;
            this.reservasHechas = reservasHechas;
        }
    }

    /** Devuelve null si no hay ruta; en ese caso reservasHechas contiene el rollback pendiente. */
    // static ResultadoAstar aStar(
    //         String origen, String destino,
    //         Map<String, List<Vuelo>> vuelosPorOrigen,
    //         Map<String, Aeropuerto>  aeropuertos,
    //         Map<String, EstadoAlmacen> capAlmacenes,
    //         EstadoVuelos estadoVuelos,
    //         LocalDateTime tInicioUtc,
    //         int cantidad) {

    //     if (origen.equals(destino)) {
    //         return new ResultadoAstar(
    //             Collections.singletonList(origen),
    //             Collections.emptyList(), 0.0, Collections.emptyList());
    //     }

    //     PriorityQueue<Nodo>              abiertos  = new PriorityQueue<>();
    //     Map<String, Double>              visitados = new HashMap<>();
    //     List<Vuelo>                      reservas  = new ArrayList<>();

    //     abiertos.add(new Nodo(origen, tInicioUtc, 0.0, null, null));

    //     while (!abiertos.isEmpty()) {
    //         Nodo actual = abiertos.poll();

    //         if (actual.aeropuerto.equals(destino)) {
    //             // Reconstruir camino
    //             List<String> ruta       = new ArrayList<>();
    //             List<Vuelo>  vuelosUsad = new ArrayList<>();
    //             Nodo cur = actual;
    //             while (cur != null) {
    //                 ruta.add(cur.aeropuerto);
    //                 if (cur.vuelo != null) vuelosUsad.add(cur.vuelo);
    //                 cur = cur.padre;
    //             }
    //             Collections.reverse(ruta);
    //             Collections.reverse(vuelosUsad);
    //             return new ResultadoAstar(ruta, vuelosUsad, actual.costo, reservas);
    //         }

    //         String clave = actual.aeropuerto + "@" + actual.tiempoUtc;
    //         if (visitados.containsKey(clave) && visitados.get(clave) <= actual.costo)
    //             continue;
    //         visitados.put(clave, actual.costo);

    //         List<Vuelo> vecinos = vuelosPorOrigen.getOrDefault(
    //             actual.aeropuerto, Collections.emptyList());

    //         for (Vuelo vuelo : vecinos) {
    //             // [FIX-1] Anclar horarios UTC al día del nodo actual
    //             LocalDate diaActual = actual.tiempoUtc.toLocalDate();
    //             LocalDateTime salUtc  = LocalDateTime.of(diaActual, vuelo.salidaUtc);
    //             LocalDateTime llegUtc = LocalDateTime.of(diaActual, vuelo.llegadaUtc);

    //             // Cruza medianoche UTC
    //             if (!llegUtc.isAfter(salUtc)) llegUtc = llegUtc.plusDays(1);
    //             // Vuelo ya salió → diferir al día siguiente
    //             if (!salUtc.isAfter(actual.tiempoUtc)) {
    //                 salUtc  = salUtc.plusDays(1);
    //                 llegUtc = llegUtc.plusDays(1);
    //             }

    //             double esperaH   = Duration.between(actual.tiempoUtc, salUtc).toMinutes()  / 60.0;
    //             double duracionH = Duration.between(salUtc,  llegUtc).toMinutes()           / 60.0;
    //             if (esperaH < 0 || duracionH <= 0) continue;

    //             String destVuelo = vuelo.destino;
    //             if (!capAlmacenes.containsKey(destVuelo)) continue;

    //             // [FIX-2] Capacidad del vuelo (compartida, thread-safe)
    //             if (!estadoVuelos.hayCapacidad(vuelo, cantidad)) continue;

    //             // Capacidad del almacén destino (copia local)
    //             EstadoAlmacen ea = capAlmacenes.get(destVuelo);
    //             if (!hayCapacidadAlmacen(ea, llegUtc, cantidad)) continue;

    //             // [FIX-3] Reserva atómica en el vuelo compartido
    //             if (!estadoVuelos.reservar(vuelo, cantidad)) continue;
    //             reservas.add(vuelo);

    //             // Registrar en almacén local
    //             registrarOcupacion(ea, llegUtc, cantidad);

    //             double nuevoCosto = actual.costo + esperaH + duracionH;
    //             double h          = heuristica(destVuelo, destino, aeropuertos);

    //             abiertos.add(new Nodo(destVuelo, llegUtc,
    //                                   nuevoCosto + h, actual, vuelo));
    //         }
    //     }
    //     // Sin ruta — reservas contiene lo que hay que hacer rollback
    //     return null;
    // }
    static ResultadoAstar aStar(
        String origen, String destino,
        Map<String, List<Vuelo>> vuelosPorOrigen,
        Map<String, Aeropuerto> aeropuertos,
        Map<String, EstadoAlmacen> capAlmacenes,
        EstadoVuelos estadoVuelos,
        LocalDateTime tInicioUtc,
        int cantidad,
        long seed) {

    Random rng = new Random(seed);

    if (origen.equals(destino)) {
        return new ResultadoAstar(
            Collections.singletonList(origen),
            Collections.emptyList(), 0.0, Collections.emptyList());
    }

    PriorityQueue<Nodo> abiertos = new PriorityQueue<>();
    Map<String, Double> visitados = new HashMap<>();
    List<Vuelo> reservas = new ArrayList<>();

    abiertos.add(new Nodo(origen, tInicioUtc, 0.0, null, null));

    while (!abiertos.isEmpty()) {
        Nodo actual = abiertos.poll();

        if (actual.aeropuerto.equals(destino)) {
            List<String> ruta = new ArrayList<>();
            List<Vuelo> vuelosUsad = new ArrayList<>();
            Nodo cur = actual;

            while (cur != null) {
                ruta.add(cur.aeropuerto);
                if (cur.vuelo != null) vuelosUsad.add(cur.vuelo);
                cur = cur.padre;
            }

            Collections.reverse(ruta);
            Collections.reverse(vuelosUsad);

            return new ResultadoAstar(ruta, vuelosUsad, actual.costo, reservas);
        }

        String clave = actual.aeropuerto + "@" + actual.tiempoUtc;
        if (visitados.containsKey(clave) && visitados.get(clave) <= actual.costo)
            continue;
        visitados.put(clave, actual.costo);

        // 🔀 Mezclar vecinos
        List<Vuelo> vecinos = new ArrayList<>(
            vuelosPorOrigen.getOrDefault(actual.aeropuerto, Collections.emptyList())
        );
        Collections.shuffle(vecinos, rng);

        for (Vuelo vuelo : vecinos) {

            LocalDate diaActual = actual.tiempoUtc.toLocalDate();
            LocalDateTime salUtc  = LocalDateTime.of(diaActual, vuelo.salidaUtc);
            LocalDateTime llegUtc = LocalDateTime.of(diaActual, vuelo.llegadaUtc);

            if (!llegUtc.isAfter(salUtc)) llegUtc = llegUtc.plusDays(1);

            if (!salUtc.isAfter(actual.tiempoUtc)) {
                salUtc  = salUtc.plusDays(1);
                llegUtc = llegUtc.plusDays(1);
            }

            double esperaH   = Duration.between(actual.tiempoUtc, salUtc).toMinutes() / 60.0;
            double duracionH = Duration.between(salUtc, llegUtc).toMinutes() / 60.0;
            if (esperaH < 0 || duracionH <= 0) continue;

            String destVuelo = vuelo.destino;
            if (!capAlmacenes.containsKey(destVuelo)) continue;

            if (!estadoVuelos.hayCapacidad(vuelo, cantidad)) continue;

            EstadoAlmacen ea = capAlmacenes.get(destVuelo);
            if (!hayCapacidadAlmacen(ea, llegUtc, cantidad)) continue;

            if (!estadoVuelos.reservar(vuelo, cantidad)) continue;
            reservas.add(vuelo);

            registrarOcupacion(ea, llegUtc, cantidad);

            double nuevoCosto = actual.costo + esperaH + duracionH;
            double h = heuristica(destVuelo, destino, aeropuertos);

            // 🎲 Ruido pequeño para romper empates
            double ruido = rng.nextDouble() * 1e-6;

            abiertos.add(new Nodo(
                destVuelo,
                llegUtc,
                nuevoCosto + h + ruido,
                actual,
                vuelo
            ));
        }
    }

    return null;
    }


    // ═══════════════════════════════════════════════════════════════════════
    // PLANIFICAR UN ENVÍO
    // ═══════════════════════════════════════════════════════════════════════

    static Object planificarEnvio(
            Envio envio,
            Map<String, List<Vuelo>> vuelosPorOrigen,
            Map<String, Aeropuerto>  aeropuertos,
            Map<String, EstadoAlmacen> capBase,
            EstadoVuelos estadoVuelos) {

        // Clonar almacenes (copia local por envío)
        Map<String, EstadoAlmacen> capLocal = new HashMap<>();
        for (Map.Entry<String, EstadoAlmacen> e : capBase.entrySet())
            capLocal.put(e.getKey(), e.getValue().clonar());

        List<Vuelo> reservas = new ArrayList<>();
        ResultadoAstar res = aStar(
            envio.origen, envio.destino,
            vuelosPorOrigen, aeropuertos,
            capLocal, estadoVuelos,
            envio.horaUtc, envio.cantidad);

        if (res == null) {
            // Rollback
            for (Vuelo v : reservas) estadoVuelos.liberar(v, envio.cantidad);
            return new ResultadoFail(envio, "Sin ruta disponible (A* sin solución)");
        }

        // Calcular plazo
        boolean mismoCont   = mismoContiente(envio.origen, envio.destino);
        int     plazoDias   = mismoCont ? 1 : 2;
        double  diasViaje   = res.costoHoras / 24.0;
        boolean cumplePlazo = diasViaje <= plazoDias;
        int     escalas     = res.ruta.size() - 2;

        // Snapshot de ocupación para el reporte
        int[] ocup = new int[res.vuelosUsados.size()];
        for (int i = 0; i < res.vuelosUsados.size(); i++) {
            ocup[i] = estadoVuelos.ocupacion(res.vuelosUsados.get(i))[0];
        }

        return new ResultadoOk(envio, res.ruta, res.vuelosUsados,
                               res.costoHoras, cumplePlazo, plazoDias, escalas, ocup);
    }

    // ═══════════════════════════════════════════════════════════════════════
    // PLANIFICACIÓN POR LOTES  [FIX-3]
    // ═══════════════════════════════════════════════════════════════════════

    static class ResultadoLote {
        final List<ResultadoOk>   ok   = new ArrayList<>();
        final List<ResultadoFail> fail = new ArrayList<>();
    }

    static ResultadoLote planificarLote(
            List<Envio> envios,
            Map<String, List<Vuelo>> vuelosPorOrigen,
            Map<String, Aeropuerto>  aeropuertos,
            Map<String, EstadoAlmacen> capBase,
            EstadoVuelos estadoVuelos,
            int maxWorkers,
            boolean mostrarProgreso) throws InterruptedException, ExecutionException {

        // [FIX-3] Ordenar por hora UTC (FIFO)
        List<Envio> ordenados = envios.stream()
            .sorted(Comparator.comparing(e -> e.horaUtc))
            .collect(Collectors.toList());

        ResultadoLote resultado = new ResultadoLote();
        AtomicInteger procesados = new AtomicInteger(0);
        int total = ordenados.size();
        long t0 = System.currentTimeMillis();

        ExecutorService pool = Executors.newFixedThreadPool(maxWorkers);
        List<Future<Object>> futuros = new ArrayList<>();

        for (Envio envio : ordenados) {
            futuros.add(pool.submit(() ->
                planificarEnvio(envio, vuelosPorOrigen, aeropuertos,
                                capBase, estadoVuelos)));
        }
        pool.shutdown();

        for (Future<Object> f : futuros) {
            Object res = f.get();
            if (res instanceof ResultadoOk)   resultado.ok.add((ResultadoOk) res);
            else                               resultado.fail.add((ResultadoFail) res);

            int n = procesados.incrementAndGet();
            if (mostrarProgreso && (n % 100 == 0 || n == total)) {
                long elapsed = System.currentTimeMillis() - t0;
                long eta     = n > 0 ? (elapsed / n) * (total - n) : 0;
                System.out.printf("\r  [%7d/%d]  %5.1f%%  Elapsed: %s  ETA: %s  " +
                                  "OK: %d  Fail: %d",
                    n, total, n * 100.0 / total,
                    fmtTiempo(elapsed / 1000), fmtTiempo(eta / 1000),
                    resultado.ok.size(), resultado.fail.size());
            }
        }
        if (mostrarProgreso) System.out.println();
        return resultado;
    }

    // ═══════════════════════════════════════════════════════════════════════
    // UTILIDADES
    // ═══════════════════════════════════════════════════════════════════════

    static final Map<Character, String> PREFIJOS = new HashMap<>();
    static {
        PREFIJOS.put('S', "América del Sur");   PREFIJOS.put('K', "América del Norte");
        PREFIJOS.put('M', "América Central");   PREFIJOS.put('T', "Caribe");
        PREFIJOS.put('E', "Europa Norte");       PREFIJOS.put('L', "Europa Sur/Med.");
        PREFIJOS.put('U', "Europa Este/Asia C"); PREFIJOS.put('O', "Medio Oriente/Asia S");
        PREFIJOS.put('V', "Asia Sur/Sureste");   PREFIJOS.put('R', "Asia del Este");
        PREFIJOS.put('W', "Asia Sureste");        PREFIJOS.put('Y', "Oceanía");
        PREFIJOS.put('F', "África Sur");          PREFIJOS.put('G', "África Oeste");
        PREFIJOS.put('H', "África Este/Norte");   PREFIJOS.put('Z', "China");
    }

    static String continente(String icao) {
        return PREFIJOS.getOrDefault(Character.toUpperCase(icao.charAt(0)), "Desconocido");
    }

    static boolean mismoContiente(String a, String b) {
        return continente(a).equals(continente(b));
    }

    static String fmtTiempo(long segundos) {
        long h = segundos / 3600, m = (segundos % 3600) / 60, s = segundos % 60;
        return h > 0 ? String.format("%dh %02dm %02ds", h, m, s)
                     : String.format("%02dm %02ds", m, s);
    }

    static String fmtVuelo(Vuelo v, int ocup) {
        return String.format("%s→%s  sal.%s local(UTC%+d) / lleg.%s local(UTC%+d)  " +
                             "cap=%d  [ocup=%d]",
            v.origen, v.destino,
            FMT_TIME.format(v.salidaLocal),  v.tzOrig,
            FMT_TIME.format(v.llegadaLocal), v.tzDest,
            v.capacidad, ocup);
    }

    // ═══════════════════════════════════════════════════════════════════════
    // GENERACIÓN DE REPORTE
    // ═══════════════════════════════════════════════════════════════════════

    static void generarReporte(
            List<ResultadoOk>   ok,
            List<ResultadoFail> fail,
            List<ErrorFormato>  errores,
            String origenLabel,
            String pathSalida,
            Map<String, Aeropuerto> aeropuertos,
            LocalDate fechaIni, int diasSim,
            EstadoVuelos estadoVuelos) throws IOException {

        int total    = ok.size() + fail.size();
        int nOk      = ok.size(), nFail = fail.size(), nErr = errores.size();
        int nCumple  = (int) ok.stream().filter(r -> r.cumplePlazo).count();
        OptionalDouble avgH = ok.stream().mapToDouble(r -> r.costoHoras).average();
        OptionalDouble minH = ok.stream().mapToDouble(r -> r.costoHoras).min();
        OptionalDouble maxH = ok.stream().mapToDouble(r -> r.costoHoras).max();
        OptionalDouble avgE = ok.stream().mapToDouble(r -> r.escalas).average();

        String ts = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        List<String> L = new ArrayList<>();
        Runnable nl = () -> L.add("");

        L.add(SEP_MAYOR);
        L.add("  TASF.B2B — REPORTE DE PLANIFICACIÓN DE RUTAS DE MALETAS  (Java)");
        L.add(SEP_MAYOR);
        L.add(String.format("  Generado       : %s", ts));
        L.add(String.format("  Origen(es)     : %s", origenLabel));
        L.add(String.format("  Fecha inicio   : %s", FMT_DATE.format(fechaIni)));
        L.add(String.format("  Días simulados : %d (hasta %s exclusive)",
              diasSim, FMT_DATE.format(fechaIni.plusDays(diasSim))));
        L.add("  Algoritmo      : A* con heurística Haversine (ref. 900 km/h)");
        L.add("  [FIX-1]        : Horarios en hora LOCAL → convertidos a UTC");
        L.add("  [FIX-2]        : Capacidad de vuelo compartida (thread-safe)");
        L.add("  [FIX-3]        : Un vuelo puede transportar múltiples envíos");
        L.add("  Política       : mismo continente ≤ 1 día | distinto ≤ 2 días");
        L.add(SEP_MENOR);
        nl.run();
        L.add("  RESUMEN EJECUTIVO");
        L.add(SEP_MENOR);
        L.add(String.format("  Total de envíos procesados       : %,10d", total));
        if (total > 0) {
            L.add(String.format("  Rutas encontradas (OK)           : %,10d   (%.1f%%)",
                  nOk,   nOk   * 100.0 / total));
            L.add(String.format("  Sin ruta (FALLIDOS)              : %,10d   (%.1f%%)",
                  nFail, nFail * 100.0 / total));
        }
        L.add(String.format("  Errores de formato               : %,10d", nErr));
        if (nOk > 0) {
            L.add(String.format("  Cumplen plazo                    : %,10d   (%.1f%% de OK)",
                  nCumple, nCumple * 100.0 / nOk));
            L.add(String.format("  Tiempo promedio viaje (UTC)      : %10.2fh  (%.3f días)",
                  avgH.orElse(0), avgH.orElse(0) / 24));
            L.add(String.format("  Tiempo mín / máx                 : %10.2fh  /  %.2fh",
                  minH.orElse(0), maxH.orElse(0)));
            L.add(String.format("  Escalas promedio                 : %10.2f", avgE.orElse(0)));
        }
        nl.run(); L.add(SEP_MAYOR);

        // ── SECCIÓN 1: Rutas planificadas ───────────────────────────────────
        nl.run();
        L.add(String.format("  SECCIÓN 1 — RUTAS PLANIFICADAS (%,d envíos)", nOk));
        L.add(SEP_MENOR); nl.run();
        for (int i = 0; i < ok.size(); i++) {
            ResultadoOk r = ok.get(i);
            Envio env = r.envio;
            int tzO = aeropuertos.containsKey(env.origen)
                      ? aeropuertos.get(env.origen).tzOffset : 0;
            L.add(String.format("  [%8d]  Envío %-10s  ·  Cliente %s",
                  i + 1, env.idEnvio, env.idCliente));
            L.add(String.format("           Origen   : %s (UTC%+d)  [%s]",
                  env.origen, tzO, continente(env.origen)));
            L.add(String.format("           Destino  : %s  [%s]",
                  env.destino, continente(env.destino)));
            L.add(String.format("           Recepción: %s local  →  %s UTC",
                  FMT_DATETIME.format(env.horaLocal),
                  FMT_DATETIME.format(env.horaUtc)));
            L.add(String.format("           Cantidad : %3d maleta(s)", env.cantidad));
            L.add(String.format("           Tipo     : %s  |  Plazo: %d día(s)  |  " +
                                "Viaje: %.2fh (%.3f días)  |  %s",
                  mismoContiente(env.origen, env.destino) ? "Mismo continente"
                                                          : "Intercontinental",
                  r.plazoDias, r.costoHoras, r.costoHoras / 24,
                  r.cumplePlazo ? "CUMPLE PLAZO" : "FUERA DE PLAZO"));
            L.add("           Ruta     : " + String.join(" → ", r.ruta));
            if (!r.vuelos.isEmpty()) {
                L.add("           Vuelos   :");
                for (int j = 0; j < r.vuelos.size(); j++) {
                    int ocup = j < r.ocupacionVuelos.length ? r.ocupacionVuelos[j] : 0;
                    L.add("             " + (j + 1) + ". " + fmtVuelo(r.vuelos.get(j), ocup));
                }
            }
            if (i < ok.size() - 1) L.add(SEP_SECC);
        }
        nl.run(); L.add(SEP_MAYOR);

        // ── SECCIÓN 2: Sin ruta ─────────────────────────────────────────────
        nl.run();
        L.add(String.format("  SECCIÓN 2 — ENVÍOS SIN RUTA (%,d registros)", nFail));
        L.add(SEP_MENOR); nl.run();
        if (nFail == 0) {
            L.add("  (Ningún envío quedó sin ruta.)");
        } else {
            Map<String, Long> causas = fail.stream()
                .collect(Collectors.groupingBy(r -> r.motivo, Collectors.counting()));
            L.add("  Causas:");
            causas.entrySet().stream()
                .sorted((a, b) -> Long.compare(b.getValue(), a.getValue()))
                .forEach(e -> L.add(String.format("    • %-60s  %,6d", e.getKey(), e.getValue())));
            nl.run();
            Map<String, Long> destFail = fail.stream()
                .collect(Collectors.groupingBy(r -> r.envio.destino, Collectors.counting()));
            L.add("  Destinos afectados:");
            destFail.entrySet().stream()
                .sorted((a, b) -> Long.compare(b.getValue(), a.getValue()))
                .forEach(e -> L.add(String.format("    • %s  (%,d envíos)  —  En BD: %s",
                    e.getKey(), e.getValue(),
                    aeropuertos.containsKey(e.getKey()) ? "Sí" : "NO")));
            nl.run(); L.add("  Detalle:"); nl.run();
            for (int i = 0; i < fail.size(); i++) {
                ResultadoFail r = fail.get(i);
                Envio env = r.envio;
                int tzO = aeropuertos.containsKey(env.origen)
                          ? aeropuertos.get(env.origen).tzOffset : 0;
                L.add(String.format("  [%6d]  Envío %-10s  ·  Cliente %s",
                      i + 1, env.idEnvio, env.idCliente));
                L.add(String.format("          %s(UTC%+d) → %s  |  %s local / %s UTC  |  %d maleta(s)",
                      env.origen, tzO, env.destino,
                      FMT_DATETIME.format(env.horaLocal),
                      FMT_DATETIME.format(env.horaUtc).substring(11), env.cantidad));
                L.add("          Motivo: " + r.motivo);
                if (i < fail.size() - 1) nl.run();
            }
        }
        nl.run(); L.add(SEP_MAYOR);

        // ── SECCIÓN 3: Errores de formato ───────────────────────────────────
        if (!errores.isEmpty()) {
            nl.run();
            L.add(String.format("  SECCIÓN 3 — ERRORES DE FORMATO (%,d líneas)", nErr));
            L.add(SEP_MENOR); nl.run();
            for (ErrorFormato ef : errores) {
                L.add(String.format("  Línea %8d: %s", ef.numLinea, ef.raw));
                L.add("             Motivo: " + ef.motivo);
                nl.run();
            }
            L.add(SEP_MAYOR);
        }

        nl.run();
        L.add("  Fin del reporte  ·  " + ts);
        L.add(SEP_MAYOR);

        Files.write(Paths.get(pathSalida),
                    L, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
    }

    static void generarReporteCsv(
            List<ResultadoOk>   ok,
            List<ResultadoFail> fail,
            String pathSalida) throws IOException {

        List<String> lines = new ArrayList<>();
        lines.add("id_envio,id_cliente,origen,destino,hora_local,hora_utc," +
                  "cantidad,estado,costo_horas,dias_viaje,escalas," +
                  "cumple_plazo,plazo_dias,ruta,num_vuelos,motivo_fallo");

        for (ResultadoOk r : ok) {
            Envio e = r.envio;
            lines.add(String.join(",",
                e.idEnvio, e.idCliente, e.origen, e.destino,
                FMT_DATETIME.format(e.horaLocal),
                FMT_DATETIME.format(e.horaUtc),
                String.valueOf(e.cantidad), "OK",
                String.format("%.2f", r.costoHoras),
                String.format("%.4f", r.costoHoras / 24),
                String.valueOf(r.escalas),
                String.valueOf(r.cumplePlazo),
                String.valueOf(r.plazoDias),
                "\"" + String.join(" > ", r.ruta) + "\"",
                String.valueOf(r.vuelos.size()), ""));
        }
        for (ResultadoFail r : fail) {
            Envio e = r.envio;
            lines.add(String.join(",",
                e.idEnvio, e.idCliente, e.origen, e.destino,
                FMT_DATETIME.format(e.horaLocal),
                FMT_DATETIME.format(e.horaUtc),
                String.valueOf(e.cantidad), "FALLIDO",
                "", "", "", "", "", "", "",
                "\"" + r.motivo.replace("\"", "'") + "\""));
        }
        Files.write(Paths.get(pathSalida), lines, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
    }

    // ═══════════════════════════════════════════════════════════════════════
    // MAIN
    // ═══════════════════════════════════════════════════════════════════════

    public static void main(String[] args) throws Exception {

        // ── Parseo de argumentos ──────────────────────────────────────────
        Map<String, String> params = new HashMap<>();
        for (int i = 0; i < args.length - 1; i += 2) {
            params.put(args[i].replaceFirst("^--", ""), args[i + 1]);
        }
        // Valores por defecto
        String  pathAerop  = params.getOrDefault("aeropuertos", "aeropuertos.txt");
        String  pathVuelos = params.getOrDefault("vuelos",      "vuelos.txt");
        String  dirEnvios  = params.getOrDefault("directorio",  ".");
        String  salida     = params.getOrDefault("salida",      "reporte_planificacion");
        int     workers    = Integer.parseInt(params.getOrDefault("workers", "4"));
        int     limite     = params.containsKey("limite")
                             ? Integer.parseInt(params.get("limite")) : Integer.MAX_VALUE;
        LocalDate fechaIni = params.containsKey("fecha-ini")
                             ? LocalDate.parse(params.get("fecha-ini"), FMT_DATE)
                             : LocalDate.of(2026, 1, 2);
        int diasSim        = Integer.parseInt(params.getOrDefault("dias", "5"));

        System.out.println("═".repeat(70));
        System.out.println("  TASF.B2B — Planificador de Rutas  (A*)  Java  [FIX-1/2/3]");
        System.out.println("═".repeat(70));
        System.out.printf("%n  Rango de simulación: %s  →  %s (%d días)%n",
            FMT_DATE.format(fechaIni),
            FMT_DATE.format(fechaIni.plusDays(diasSim)), diasSim);
        System.out.printf("  Workers: %d%n%n", workers);

        // ── Fase 1: Aeropuertos ───────────────────────────────────────────
        System.out.printf("[1/4] Cargando aeropuertos desde '%s' …%n", pathAerop);
        Map<String, Aeropuerto> aeropuertos = leerAeropuertos(pathAerop);
        System.out.printf("      %d aeropuertos cargados.%n", aeropuertos.size());

        // ── Fase 2: Vuelos ────────────────────────────────────────────────
        System.out.printf("%n[2/4] Cargando vuelos desde '%s' …%n", pathVuelos);
        List<Vuelo> vuelos = leerVuelos(pathVuelos, aeropuertos);
        System.out.printf("      %,d vuelos cargados (horarios local→UTC aplicados).%n",
                          vuelos.size());
        // Índice por aeropuerto origen
        Map<String, List<Vuelo>> vuelosPorOrigen = vuelos.stream()
            .collect(Collectors.groupingBy(v -> v.origen));

        // Estado compartido de vuelos
        EstadoVuelos estadoVuelos = new EstadoVuelos(vuelos);

        // Mapa base de almacenes
        Map<String, EstadoAlmacen> capBase = new LinkedHashMap<>();
        for (Map.Entry<String, Aeropuerto> e : aeropuertos.entrySet())
            capBase.put(e.getKey(), new EstadoAlmacen(e.getValue().capMax));

        // ── Fase 3: Envíos ────────────────────────────────────────────────
        System.out.printf("%n[3/4] Descubriendo archivos de envíos en '%s' …%n", dirEnvios);
        List<LecturaEnvios> lecturas =
            leerEnviosPorAeropuerto(dirEnvios, aeropuertos, fechaIni, diasSim);

        List<ResultadoOk>   todosOk   = new ArrayList<>();
        List<ResultadoFail> todosFail = new ArrayList<>();
        List<ErrorFormato>  todosErr  = new ArrayList<>();
        List<String>        origenes  = new ArrayList<>();

        for (LecturaEnvios lr : lecturas) {
            origenes.add(lr.origen);
            todosErr.addAll(lr.erroresFormato);

            List<Envio> envios = lr.envios;
            if (envios.isEmpty()) {
                System.out.printf("  [SKIP] %s: sin envíos en el rango indicado.%n", lr.origen);
                continue;
            }
            if (limite < envios.size()) {
                System.out.printf("  [LIMITE] %s: procesando %d de %,d envíos.%n",
                    lr.origen, limite, envios.size());
                envios = envios.subList(0, limite);
            }

            long t0 = System.currentTimeMillis();
            ResultadoLote lote = planificarLote(
                envios, vuelosPorOrigen, aeropuertos, capBase,
                estadoVuelos, workers, true);
            long elapsed = System.currentTimeMillis() - t0;

            System.out.printf("%n  %s  completado en %s  |  OK: %,d  |  FAIL: %,d%n",
                lr.origen, fmtTiempo(elapsed / 1000),
                lote.ok.size(), lote.fail.size());

            todosOk.addAll(lote.ok);
            todosFail.addAll(lote.fail);
        }

        // ── Fase 4: Reporte ───────────────────────────────────────────────
        System.out.printf("%n[4/4] Generando reporte …%n");
        String label   = origenes.isEmpty() ? "MULTI" : String.join("+", origenes);
        String pathTxt = salida + ".txt";
        String pathCsv = salida + ".csv";

        generarReporte(todosOk, todosFail, todosErr, label, pathTxt,
                       aeropuertos, fechaIni, diasSim, estadoVuelos);
        generarReporteCsv(todosOk, todosFail, pathCsv);
        System.out.printf("  %s%n  %s%n", pathTxt, pathCsv);

        // ── Resumen final ─────────────────────────────────────────────────
        int total = todosOk.size() + todosFail.size();
        System.out.println("\n" + "═".repeat(70));
        System.out.println("  RESUMEN FINAL");
        System.out.println("─".repeat(70));
        if (total > 0) {
            System.out.printf("  Total        : %,d%n", total);
            System.out.printf("  OK           : %,d  (%.1f%%)%n",
                todosOk.size(),   todosOk.size()   * 100.0 / total);
            System.out.printf("  FALLIDOS     : %,d  (%.1f%%)%n",
                todosFail.size(), todosFail.size() * 100.0 / total);
        }
        if (!todosOk.isEmpty()) {
            long nCumple = todosOk.stream().filter(r -> r.cumplePlazo).count();
            System.out.printf("  Cumplen plazo: %,d  (%.1f%% de OK)%n",
                nCumple, nCumple * 100.0 / todosOk.size());
        }
        System.out.println("═".repeat(70));
    }
}
