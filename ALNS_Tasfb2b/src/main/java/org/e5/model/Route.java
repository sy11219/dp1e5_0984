package org.e5.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Representa la ruta asignada a un envío: secuencia ordenada de vuelos que
 * llevará las maletas desde el aeropuerto origen hasta el destino final.
 *
 * Una ruta puede ser:
 * - Directa: un solo vuelo origen→destino
 * - Con escalas: múltiples vuelos con aeropuertos intermedios
 *
 * La clase calcula automáticamente:
 * - Tiempo total de tránsito (incluyendo 10 min de escala por aeropuerto intermedio)
 * - Minuto absoluto de llegada al destino final
 * - Si la ruta es válida (sin solapamientos, con espacio en vuelos y aeropuertos)
 *
 * Esta clase es inmutable en su lista de vuelos una vez construida, para
 * garantizar integridad durante la evaluación de la función objetivo del SA.
 */
public class Route {

    private final String shipmentId;           // ID del envío al que pertenece esta ruta
    private final String originCode;           // Aeropuerto de inicio
    private final String finalDestCode;        // Aeropuerto de destino final
    private final List<Flight> flights;        // Secuencia de vuelos (en orden)
    private final int suitcaseCount;           // Maletas que viajan por esta ruta
    private final int startMinute;             // Minuto desde el que el envío está disponible

    // Tiempo en minutos que las maletas deben esperar en cada aeropuerto intermedio
    // antes de cargar en el siguiente vuelo (carga + descarga = 10 minutos)
    public static final int TRANSIT_TIME_MINUTES = 10;

    /**
     * Constructor de ruta.
     *
     * @param shipmentId    ID del envío
     * @param originCode    Código ICAO del aeropuerto origen
     * @param finalDestCode Código ICAO del destino final
     * @param flights       Lista ordenada de vuelos que componen la ruta
     * @param suitcaseCount Número de maletas en el envío
     * @param startMinute   Minuto absoluto desde el que el envío está listo para salir
     */
    public Route(String shipmentId, String originCode, String finalDestCode,
                 List<Flight> flights, int suitcaseCount, int startMinute) {
        this.shipmentId    = shipmentId;
        this.originCode    = originCode;
        this.finalDestCode = finalDestCode;
        this.flights       = Collections.unmodifiableList(new ArrayList<>(flights));
        this.suitcaseCount = suitcaseCount;
        this.startMinute   = startMinute;
    }

    // ── Cálculos de la ruta ──────────────────────────────────────────────────

    /**
     * Calcula el minuto absoluto en que el envío llega al destino final.
     *
     * Proceso:
     *   Para cada vuelo en secuencia:
     *     1. El envío debe estar listo ANTES de la hora de salida del vuelo
     *     2. Se suma el tiempo de tránsito (10 min) al llegar a cada escala intermedia
     *     3. El tiempo total = llegada del último vuelo
     *
     * @return Minuto absoluto de llegada, o Integer.MAX_VALUE si la ruta es inválida
     */
    public int calculateArrivalMinute() {
        if (flights.isEmpty()) return Integer.MAX_VALUE;

        int currentReadyMinute = startMinute; // Cuando el envío está listo para su primer vuelo

        for (int i = 0; i < flights.size(); i++) {
            Flight f = flights.get(i);

            // El envío debe salir DESPUÉS de estar listo; si el vuelo sale antes, ruta inválida
            if (f.absoluteDepartureMinute() < currentReadyMinute) {
                return Integer.MAX_VALUE; // Vuelo sale antes que el envío esté listo
            }

            // Llegamos al siguiente aeropuerto
            currentReadyMinute = f.absoluteArrivalMinute();

            // Si hay más vuelos (escala), sumar 10 minutos de carga/descarga
            if (i < flights.size() - 1) {
                currentReadyMinute += TRANSIT_TIME_MINUTES;
            }
        }

        return currentReadyMinute; // Minuto de llegada al destino final
    }

    /**
     * Verifica que la ruta sea lógicamente válida:
     * 1. El primer vuelo parte del aeropuerto origen del envío
     * 2. El último vuelo llega al destino final
     * 3. Los aeropuertos de transferencia son consecutivos y coherentes
     * 4. No hay vuelos que salgan antes que el envío esté listo
     *
     * @return true si la ruta es válida
     */
    public boolean isValid() {
        if (flights.isEmpty()) return false;

        // Primer vuelo debe partir del origen del envío
        if (!flights.get(0).getOriginCode().equals(originCode)) return false;

        // Último vuelo debe llegar al destino final
        if (!flights.get(flights.size() - 1).getDestCode().equals(finalDestCode)) return false;

        // Las escalas deben ser consecutivas: destino de un vuelo = origen del siguiente
        for (int i = 0; i < flights.size() - 1; i++) {
            if (!flights.get(i).getDestCode().equals(flights.get(i + 1).getOriginCode())) {
                return false;
            }
        }

        // Verificar que los tiempos sean coherentes (el envío puede alcanzar cada vuelo)
        return calculateArrivalMinute() != Integer.MAX_VALUE;
    }

    /**
     * Genera una descripción textual de la ruta para reportes y consola.
     * Formato: ORIG → (escala1) → (escala2) → DEST
     *
     * @return Cadena descriptiva de la ruta con vuelos, horarios y capacidades
     */
    public String toReportString() {
        StringBuilder sb = new StringBuilder();
        sb.append("Ruta: ").append(originCode);
        int currentReady = startMinute;
        for (int i = 0; i < flights.size(); i++) {
            Flight f = flights.get(i);
            sb.append(String.format(" --[Vuelo %s Dia%d %s->%s]-> %s",
                    f.getFlightId(),
                    f.getDayOffset(),
                    Flight.minutesToHHMM(f.getDepartureMinute()),
                    Flight.minutesToHHMM(f.getArrivalMinute()),
                    f.getDestCode()));
            if (i < flights.size() - 1) {
                sb.append(String.format(" (+%dmin escala)", TRANSIT_TIME_MINUTES));
            }
        }
        int arrival = calculateArrivalMinute();
        sb.append(String.format(" | Llegada estimada: min %d (%s Dia %d)",
                arrival,
                Flight.minutesToHHMM(arrival % 1440),
                arrival / 1440));
        return sb.toString();
    }

    /**
     * Número de escalas intermedias (vuelos - 1).
     * Un vuelo directo tiene 0 escalas.
     *
     * @return Número de escalas
     */
    public int getLayoverCount() {
        return Math.max(0, flights.size() - 1);
    }

    // ── Getters ──────────────────────────────────────────────────────────────

    public String       getShipmentId()    { return shipmentId; }
    public String       getOriginCode()    { return originCode; }
    public String       getFinalDestCode() { return finalDestCode; }
    public List<Flight> getFlights()       { return flights; }
    public int          getSuitcaseCount() { return suitcaseCount; }
    public int          getStartMinute()   { return startMinute; }

    @Override
    public String toString() {
        return String.format("Route[Envio:%s | %s→%s | Vuelos:%d | Maletas:%d]",
                shipmentId, originCode, finalDestCode, flights.size(), suitcaseCount);
    }
}
