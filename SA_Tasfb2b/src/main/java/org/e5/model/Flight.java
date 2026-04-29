package org.e5.model;

/**
 * Representa un vuelo programado en el plan de vuelos de TASF.B2B.
 *
 * Cada vuelo tiene:
 * - Aeropuerto origen y destino (códigos ICAO)
 * - Hora de salida y llegada (expresadas en minutos desde medianoche)
 * - Capacidad máxima de maletas
 * - Carga actual asignada (maletas ya planificadas en ese vuelo)
 *
 * Los vuelos son la unidad básica sobre la cual el planificador Simulated
 * Annealing asigna envíos. Un vuelo puede pertenecer a la misma jornada o
 * a jornadas futuras (simulación de múltiples días).
 *
 * NOTA: Para vuelos entre continentes distintos, el planificador respeta
 * que el tiempo de tránsito es de 1 día (1440 minutos); para mismo continente,
 * de 0.5 días (720 minutos). Esta lógica reside en el planificador usando
 * los continentes de los aeropuertos involucrados.
 */
public class Flight {

    // ── Identificación ───────────────────────────────────────────────────────
    private final String flightId;      // ID generado: "ORIG-DEST-HH:MM" único
    private final String originCode;    // Código ICAO del aeropuerto origen
    private final String destCode;      // Código ICAO del aeropuerto destino

    // ── Tiempos (en minutos desde las 00:00 del día de referencia) ───────────
    private final int departureMinute;  // Hora de salida en minutos (ej: 03:34 → 214)
    private final int arrivalMinute;    // Hora de llegada en minutos (puede superar 1440 si cruza medianoche)
    private final int dayOffset;        // Día relativo al inicio de simulación (0 = primer día, 1 = segundo...)

    // ── Capacidad ────────────────────────────────────────────────────────────
    private final int maxCapacity;      // Capacidad máxima del vuelo en maletas
    private int assignedLoad;           // Maletas ya asignadas a este vuelo

    /**
     * Constructor de vuelo.
     *
     * @param originCode       Código ICAO origen
     * @param destCode         Código ICAO destino
     * @param departureMinute  Minutos desde medianoche del origen
     * @param arrivalMinute    Minutos desde medianoche de llegada
     * @param maxCapacity      Capacidad máxima de maletas
     * @param dayOffset        Día de simulación (0-based) en que ocurre este vuelo
     */
    public Flight(String originCode, String destCode,
                  int departureMinute, int arrivalMinute,
                  int maxCapacity, int dayOffset) {
        this.originCode      = originCode;
        this.destCode        = destCode;
        this.departureMinute = departureMinute;
        this.arrivalMinute   = arrivalMinute;
        this.maxCapacity     = maxCapacity;
        this.dayOffset       = dayOffset;
        this.assignedLoad    = 0;
        // Genera un ID único para identificar el vuelo en logs y reportes
        this.flightId = String.format("%s-%s-%04d-%d", originCode, destCode, departureMinute, dayOffset);
    }

    // ── Gestión de carga ─────────────────────────────────────────────────────

    /**
     * Verifica si el vuelo puede admitir más maletas.
     *
     * @param suitcases Cantidad de maletas a agregar
     * @return true si hay espacio suficiente
     */
    public boolean hasSpaceFor(int suitcases) {
        return (assignedLoad + suitcases) <= maxCapacity;
    }

    /**
     * Asigna maletas al vuelo (reserva espacio).
     * Se llama cuando el planificador confirma que un envío irá en este vuelo.
     *
     * @param suitcases Número de maletas a asignar
     */
    public void assignLoad(int suitcases) {
        this.assignedLoad += suitcases;
    }

    /**
     * Libera maletas previamente asignadas al vuelo.
     * Se usa en el proceso de replanificación (Simulated Annealing).
     *
     * @param suitcases Número de maletas a liberar
     */
    public void releaseLoad(int suitcases) {
        this.assignedLoad = Math.max(0, this.assignedLoad - suitcases);
    }

    /**
     * Espacio disponible restante en el vuelo.
     *
     * @return Número de maletas adicionales que caben
     */
    public int availableSpace() {
        return maxCapacity - assignedLoad;
    }

    /**
     * Devuelve el tiempo absoluto de salida en minutos desde el inicio de la simulación.
     * Útil para comparar vuelos de distintos días.
     *
     * @return dayOffset * 1440 + departureMinute
     */
    public int absoluteDepartureMinute() {
        return dayOffset * 1440 + departureMinute;
    }

    /**
     * Devuelve el tiempo absoluto de llegada en minutos desde el inicio de la simulación.
     *
     * @return dayOffset * 1440 + arrivalMinute
     */
    public int absoluteArrivalMinute() {
        return dayOffset * 1440 + arrivalMinute;
    }

    // ── Getters ──────────────────────────────────────────────────────────────

    public String getFlightId()        { return flightId; }
    public String getOriginCode()      { return originCode; }
    public String getDestCode()        { return destCode; }
    public int    getDepartureMinute() { return departureMinute; }
    public int    getArrivalMinute()   { return arrivalMinute; }
    public int    getMaxCapacity()     { return maxCapacity; }
    public int    getAssignedLoad()    { return assignedLoad; }
    public int    getDayOffset()       { return dayOffset; }

    /** Reinicia la carga asignada (usado al reiniciar la simulación). */
    public void resetLoad() { this.assignedLoad = 0; }

    @Override
    public String toString() {
        return String.format("Flight[%s → %s | Day %d | Dep: %s | Arr: %s | Load: %d/%d]",
                originCode, destCode, dayOffset,
                minutesToHHMM(departureMinute),
                minutesToHHMM(arrivalMinute),
                assignedLoad, maxCapacity);
    }

    /** Convierte minutos desde medianoche a formato HH:MM legible. */
    public static String minutesToHHMM(int minutes) {
        int h = (minutes / 60) % 24;
        int m = minutes % 60;
        return String.format("%02d:%02d", h, m);
    }
}
