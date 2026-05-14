package org.e5.model;

/**
 * Representa un envío en el sistema TASF.B2B.
 *
 * Puede ser un envío original o una parte de un envío fraccionado
 * dinámicamente por el planificador ALNS cuando las maletas no caben
 * juntas en ninguna ruta disponible.
 *
 * Campos de trazabilidad de fraccionamiento:
 *   parentShipmentId  → ID del envío original (null si no es parte)
 *   splitPartIndex    → número de parte (1-based, 0 si no es parte)
 *   splitPartCount    → total de partes del envío original
 *   originalSuitcaseCount → total de maletas del envío original
 */
public class Shipment {

    // ── Datos del pedido (inmutables) ────────────────────────────────────────
    private final String shipmentId;
    private final String originCode;
    private final String destCode;
    private final int    requestMinute;
    private final int    suitcaseCount;
    private final String clientId;
    private final String rawDate;
    private final String rawHour;
    private final String rawMinute;

    // ── Trazabilidad de fraccionamiento ──────────────────────────────────────
    private final String parentShipmentId;    // null si no es parte
    private final int    splitPartIndex;      // 0 si no es parte, 1-based si lo es
    private final int    splitPartCount;      // 0 si no es parte
    private final int    originalSuitcaseCount; // igual a suitcaseCount si no es parte

    // ── Estado (mutable, calculado por el planificador) ──────────────────────
    private Route   assignedRoute;
    private int     estimatedArrival;
    private boolean onTime;
    private boolean planned;
    private int     delayMinutes;

    // ════════════════════════════════════════════════════════════════════════
    //  CONSTRUCTORES
    // ════════════════════════════════════════════════════════════════════════

    /**
     * Constructor estándar — envío completo (no fraccionado).
     */
    public Shipment(String shipmentId, String originCode, String destCode,
                    int requestMinute, int suitcaseCount, String clientId,
                    String rawDate, String rawHour, String rawMinute) {
        this.shipmentId            = shipmentId;
        this.originCode            = originCode;
        this.destCode              = destCode;
        this.requestMinute         = requestMinute;
        this.suitcaseCount         = suitcaseCount;
        this.clientId              = clientId;
        this.rawDate               = rawDate;
        this.rawHour               = rawHour;
        this.rawMinute             = rawMinute;
        this.parentShipmentId      = null;
        this.splitPartIndex        = 0;
        this.splitPartCount        = 0;
        this.originalSuitcaseCount = suitcaseCount;
        this.planned               = false;
        this.onTime                = false;
        this.delayMinutes          = 0;
    }

    /**
     * Constructor para partes fraccionadas — generado por ALNS dinámicamente.
     *
     * @param shipmentId            ID único de esta parte (ej: "000001_p1")
     * @param originCode            mismo origen que el envío original
     * @param destCode              mismo destino que el envío original
     * @param requestMinute         mismo minuto de solicitud que el original
     * @param suitcaseCount         maletas de ESTA parte (subconjunto del original)
     * @param clientId              mismo cliente que el original
     * @param rawDate               misma fecha que el original
     * @param rawHour               misma hora que el original
     * @param rawMinute             mismo minuto que el original
     * @param parentShipmentId      ID del envío original
     * @param splitPartIndex        número de parte (1, 2, 3...)
     * @param splitPartCount        total de partes en que se dividió el envío
     * @param originalSuitcaseCount total de maletas del envío original
     */
    public Shipment(String shipmentId, String originCode, String destCode,
                    int requestMinute, int suitcaseCount, String clientId,
                    String rawDate, String rawHour, String rawMinute,
                    String parentShipmentId, int splitPartIndex,
                    int splitPartCount, int originalSuitcaseCount) {
        this.shipmentId            = shipmentId;
        this.originCode            = originCode;
        this.destCode              = destCode;
        this.requestMinute         = requestMinute;
        this.suitcaseCount         = suitcaseCount;
        this.clientId              = clientId;
        this.rawDate               = rawDate;
        this.rawHour               = rawHour;
        this.rawMinute             = rawMinute;
        this.parentShipmentId      = parentShipmentId;
        this.splitPartIndex        = splitPartIndex;
        this.splitPartCount        = splitPartCount;
        this.originalSuitcaseCount = originalSuitcaseCount;
        this.planned               = false;
        this.onTime                = false;
        this.delayMinutes          = 0;
    }

    // ════════════════════════════════════════════════════════════════════════
    //  MÉTODOS DE NEGOCIO
    // ════════════════════════════════════════════════════════════════════════

    /**
     * Plazo máximo de entrega según continentes:
     *   - Mismo continente:    1440 min (1 día)
     *   - Distinto continente: 2880 min (2 días)
     */
    public static int getDeadlineMinutes(String originContinent, String destContinent) {
        return originContinent.equalsIgnoreCase(destContinent) ? 1440 : 2880;
    }

    /**
     * Registra el resultado de la planificación.
     * Llamado por el planificador al asignar una ruta.
     */
    public void setResult(Route route, int estimatedArrival, int deadlineMinutes) {
        this.assignedRoute    = route;
        this.estimatedArrival = estimatedArrival;
        this.planned          = true;
        int deadline          = requestMinute + deadlineMinutes;
        this.onTime           = (estimatedArrival <= deadline);
        this.delayMinutes     = onTime ? 0 : (estimatedArrival - deadline);
    }

    /**
     * Resetea el estado de planificación para re-simulación o fraccionamiento.
     */
    public void resetPlanningState() {
        this.assignedRoute    = null;
        this.estimatedArrival = 0;
        this.planned          = false;
        this.onTime           = false;
        this.delayMinutes     = 0;
    }

    // ════════════════════════════════════════════════════════════════════════
    //  GETTERS — DATOS DEL PEDIDO
    // ════════════════════════════════════════════════════════════════════════

    public String getShipmentId()      { return shipmentId; }
    public String getOriginCode()      { return originCode; }
    public String getDestCode()        { return destCode; }
    public int    getRequestMinute()   { return requestMinute; }
    public int    getSuitcaseCount()   { return suitcaseCount; }
    public String getClientId()        { return clientId; }
    public String getRawDate()         { return rawDate; }
    public String getRawHour()         { return rawHour; }
    public String getRawMinuteStr()    { return rawMinute; }

    // ════════════════════════════════════════════════════════════════════════
    //  GETTERS — TRAZABILIDAD DE FRACCIONAMIENTO
    // ════════════════════════════════════════════════════════════════════════

    /** true si este Shipment es una parte de un envío fraccionado. */
    public boolean isSplitPart()            { return parentShipmentId != null; }
    public String  getParentShipmentId()    { return parentShipmentId; }
    public int     getSplitPartIndex()      { return splitPartIndex; }
    public int     getSplitPartCount()      { return splitPartCount; }
    public int     getOriginalSuitcaseCount() { return originalSuitcaseCount; }

    // ════════════════════════════════════════════════════════════════════════
    //  GETTERS — ESTADO DE PLANIFICACIÓN
    // ════════════════════════════════════════════════════════════════════════

    public Route   getAssignedRoute()    { return assignedRoute; }
    public int     getEstimatedArrival() { return estimatedArrival; }
    public boolean isOnTime()            { return onTime; }
    public boolean isPlanned()           { return planned; }
    public int     getDelayMinutes()     { return delayMinutes; }

    @Override
    public String toString() {
        String partInfo = isSplitPart()
                ? String.format(" [Parte %d/%d de %s]", splitPartIndex, splitPartCount, parentShipmentId)
                : "";
        return String.format(
                "Shipment[%s%s | %s→%s | Maletas: %d | Cliente: %s | Min: %d]",
                shipmentId, partInfo, originCode, destCode, suitcaseCount,
                clientId, requestMinute);
    }
}