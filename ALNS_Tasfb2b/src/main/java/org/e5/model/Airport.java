package org.e5.model;

/**
 * Representa un aeropuerto del sistema TASF.B2B.
 *
 * Almacena todos los atributos relevantes de un aeropuerto:
 * - Código ICAO (identificador único, ej: SKBO, SEQM)
 * - Nombre de la ciudad y país
 * - Continente (clave para determinar plazo de entrega: mismo vs diferente continente)
 * - Capacidad máxima de maletas en su almacén
 * - Ocupación actual (maletas presentes en el aeropuerto en un momento dado)
 * - Coordenadas geográficas (latitud y longitud) para futuras visualizaciones
 * - GMT offset (zona horaria)
 *
 * La capacidad del aeropuerto es fundamental para el planificador:
 * si un envío va a llegar a un aeropuerto que ya está lleno o se llenará,
 * el planificador debe redirigir ese envío por otra ruta.
 */
public class Airport {

    // ── Identificación ──────────────────────────────────────────────────────
    private final String code;       // Código ICAO de 4 letras (ej: SKBO)
    private final String city;       // Ciudad (ej: Bogota)
    private final String country;    // País (ej: Colombia)
    private final String continent;  // Continente: "America del Sur", "Europa", "Asia"

    // ── Capacidad y ocupación ────────────────────────────────────────────────
    private final int maxCapacity;   // Capacidad máxima de maletas en almacén
    private int currentLoad;         // Maletas actualmente en el aeropuerto

    // ── Geografía ───────────────────────────────────────────────────────────
    private final double latitude;   // Latitud decimal
    private final double longitude;  // Longitud decimal
    private final int gmtOffset;     // Diferencia horaria GMT (ej: -5, +2, +3)

    /**
     * Constructor completo para crear un aeropuerto con todos sus atributos.
     */
    public Airport(String code, String city, String country, String continent,
                   int maxCapacity, double latitude, double longitude, int gmtOffset) {
        this.code        = code;
        this.city        = city;
        this.country     = country;
        this.continent   = continent;
        this.maxCapacity = maxCapacity;
        this.currentLoad = 0;
        this.latitude    = latitude;
        this.longitude   = longitude;
        this.gmtOffset   = gmtOffset;
    }

    // ── Gestión de capacidad ─────────────────────────────────────────────────

    /**
     * Verifica si el aeropuerto puede aceptar una cantidad adicional de maletas.
     * Se usa antes de asignar un envío a un vuelo que llega a este aeropuerto.
     *
     * @param suitcases Número de maletas a ingresar
     * @return true si hay espacio disponible
     */
    public boolean hasCapacityFor(int suitcases) {
        return (currentLoad + suitcases) <= maxCapacity;
    }

    /**
     * Registra la llegada de maletas al aeropuerto (incrementa carga actual).
     * Llamado por el simulador cuando un vuelo aterriza.
     *
     * @param suitcases Número de maletas que llegan
     */
    public void addLoad(int suitcases) {
        this.currentLoad += suitcases;
    }

    /**
     * Registra la salida de maletas del aeropuerto (reduce carga actual).
     * Llamado cuando un vuelo despega llevando maletas de este aeropuerto.
     *
     * @param suitcases Número de maletas que salen
     */
    public void removeLoad(int suitcases) {
        this.currentLoad = Math.max(0, this.currentLoad - suitcases);
    }

    /**
     * Devuelve el espacio libre disponible en el aeropuerto.
     *
     * @return Número de maletas adicionales que pueden almacenarse
     */
    public int availableSpace() {
        return maxCapacity - currentLoad;
    }

    // ── Getters ──────────────────────────────────────────────────────────────

    public String getCode()        { return code; }
    public String getCity()        { return city; }
    public String getCountry()     { return country; }
    public String getContinent()   { return continent; }
    public int    getMaxCapacity() { return maxCapacity; }
    public int    getCurrentLoad() { return currentLoad; }
    public double getLatitude()    { return latitude; }
    public double getLongitude()   { return longitude; }
    public int    getGmtOffset()   { return gmtOffset; }

    /** Restablece la carga del aeropuerto a cero (usado en re-simulación). */
    public void resetLoad() { this.currentLoad = 0; }

    @Override
    public String toString() {
        return String.format("Airport[%s | %s, %s | Continent: %s | Cap: %d/%d]",
                code, city, country, continent, currentLoad, maxCapacity);
    }
}
