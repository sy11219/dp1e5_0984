package org.e5.model;

/**
 * Representa un envío (pedido de traslado de maletas) en el sistema TASF.B2B.
 *
 * Cada envío proviene de un archivo _envios_XXXX_.txt y contiene:
 * - ID único del envío
 * - Aeropuerto origen (inferido del nombre del archivo)
 * - Aeropuerto destino (incluido en el registro)
 * - Fecha y hora de solicitud (timestamp)
 * - Cantidad de maletas
 * - ID del cliente
 *
 * El envío es la unidad que el planificador debe rutar a través de los vuelos
 * disponibles. Puede requerir uno o más vuelos (con escalas) para llegar a destino.
 *
 * La restricción de tiempo de entrega es:
 * - Mismo continente: máximo 12 horas (720 minutos)
 * - Distinto continente: máximo 24 horas (1440 minutos)
 *
 * Esta clase también lleva el estado del envío: si fue planificado, cuándo
 * llegó, si llegó a tiempo, etc.
 */
public class Shipment {

    // ── Datos del pedido (inmutables, leídos del archivo) ────────────────────
    private final String shipmentId;    // ID del envío (ej: 000000001)
    private final String originCode;    // Código ICAO del aeropuerto origen
    private final String destCode;      // Código ICAO del aeropuerto destino
    private final int    requestMinute; // Minuto absoluto desde inicio de simulación en que se solicitó
    private final int    suitcaseCount; // Cantidad de maletas
    private final String clientId;      // ID del cliente

    // Fecha/hora original del archivo para reportes legibles
    private final String rawDate;   // aaaammdd original
    private final String rawHour;   // HH original
    private final String rawMinute; // MM original

    // ── Estado del envío (mutable, calculado por el planificador) ─────────────
    private Route assignedRoute;        // Ruta asignada por el planificador
    private int    estimatedArrival;    // Minuto estimado de llegada (absoluto)
    private boolean onTime;             // ¿Llega dentro del plazo?
    private boolean planned;            // ¿Ya fue planificado?
    private int     delayMinutes;       // Minutos de retraso sobre el límite (0 si es a tiempo)

    /**
     * Constructor completo del envío.
     *
     * @param shipmentId    Identificador único del envío
     * @param originCode    Código ICAO del aeropuerto origen
     * @param destCode      Código ICAO del aeropuerto destino
     * @param requestMinute Minuto absoluto desde inicio de simulación
     * @param suitcaseCount Número de maletas
     * @param clientId      Identificador del cliente
     * @param rawDate       Fecha original (aaaammdd) para reportes
     * @param rawHour       Hora original (HH) para reportes
     * @param rawMinute     Minuto original (MM) para reportes
     */
    public Shipment(String shipmentId, String originCode, String destCode,
                    int requestMinute, int suitcaseCount, String clientId,
                    String rawDate, String rawHour, String rawMinute) {
        this.shipmentId    = shipmentId;
        this.originCode    = originCode;
        this.destCode      = destCode;
        this.requestMinute = requestMinute;
        this.suitcaseCount = suitcaseCount;
        this.clientId      = clientId;
        this.rawDate       = rawDate;
        this.rawHour       = rawHour;
        this.rawMinute     = rawMinute;
        this.planned       = false;
        this.onTime        = false;
        this.delayMinutes  = 0;
    }

    // ── Métodos de negocio ───────────────────────────────────────────────────

    /**
     * Devuelve el plazo máximo de entrega en minutos según los continentes.
     * - Mismo continente: 720 minutos (12 horas)
     * - Distinto continente: 1440 minutos (24 horas)
     *
     * @param originContinent  Continente del aeropuerto origen
     * @param destContinent    Continente del aeropuerto destino
     * @return Plazo máximo en minutos
     */
    public static int getDeadlineMinutes(String originContinent, String destContinent) {
        if (originContinent.equalsIgnoreCase(destContinent)) {
            return 1440;   // mismo continente → 24 horas
        } else {
            return 2880;  // distinto continente → 48 horas
        }
    }

    /**
     * Registra el resultado de la planificación en este envío.
     * El planificador llama este método al asignar una ruta.
     *
     * @param route             Ruta de vuelos asignada
     * @param estimatedArrival  Minuto absoluto de llegada estimado
     * @param deadlineMinutes   Plazo límite en minutos desde solicitud
     */
    public void setResult(Route route, int estimatedArrival, int deadlineMinutes) {
        this.assignedRoute     = route;
        this.estimatedArrival  = estimatedArrival;
        this.planned           = true;
        int deadline           = requestMinute + deadlineMinutes;
        // Se descuentan 10 minutos de carga/descarga por cada escala ya incluidos en la ruta
        this.onTime            = (estimatedArrival <= deadline);
        this.delayMinutes      = onTime ? 0 : (estimatedArrival - deadline);
    }

    // ── Getters ──────────────────────────────────────────────────────────────

    public String  getShipmentId()     { return shipmentId; }
    public String  getOriginCode()     { return originCode; }
    public String  getDestCode()       { return destCode; }
    public int     getRequestMinute()  { return requestMinute; }
    public int     getSuitcaseCount()  { return suitcaseCount; }
    public String  getClientId()       { return clientId; }
    public String  getRawDate()        { return rawDate; }
    public String  getRawHour()        { return rawHour; }
    public String  getRawMinuteStr()   { return rawMinute; }
    public Route   getAssignedRoute()  { return assignedRoute; }
    public int     getEstimatedArrival(){ return estimatedArrival; }
    public boolean isOnTime()          { return onTime; }
    public boolean isPlanned()         { return planned; }
    public int     getDelayMinutes()   { return delayMinutes; }

    @Override
    public String toString() {
        return String.format(
                "Shipment[%s | %s→%s | Maletas: %d | Cliente: %s | Solicitado: %s/%s:%s | requestMinute: %d]",
                shipmentId, originCode, destCode, suitcaseCount,
                clientId, rawDate, rawHour, rawMinute, requestMinute
        );
    }
}
