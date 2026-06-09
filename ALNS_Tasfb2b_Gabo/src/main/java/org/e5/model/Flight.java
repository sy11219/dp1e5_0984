package org.e5.model;

/**
 * Representa un vuelo programado en el plan de vuelos de TASF.B2B.
 */
public class Flight {

    private final String flightId;
    private final String originCode;
    private final String destCode;

    private final int departureMinute;
    private final int arrivalMinute;
    private final int dayOffset;

    private final int maxCapacity;
    private int assignedLoad;

    public Flight(String originCode, String destCode,
                  int departureMinute, int arrivalMinute,
                  int maxCapacity, int dayOffset) {
        this(String.format("%s-%s-%04d-%d", originCode, destCode, departureMinute, dayOffset),
                originCode, destCode, departureMinute, arrivalMinute, maxCapacity, dayOffset);
    }

    public Flight(String flightId, String originCode, String destCode,
                  int departureMinute, int arrivalMinute,
                  int maxCapacity, int dayOffset) {
        this.flightId = flightId;
        this.originCode = originCode;
        this.destCode = destCode;
        this.departureMinute = departureMinute;
        this.arrivalMinute = arrivalMinute;
        this.maxCapacity = maxCapacity;
        this.dayOffset = dayOffset;
        this.assignedLoad = 0;
    }

    public boolean hasSpaceFor(int suitcases) {
        return (assignedLoad + suitcases) <= maxCapacity;
    }

    public void assignLoad(int suitcases) {
        this.assignedLoad += suitcases;
    }

    public void releaseLoad(int suitcases) {
        this.assignedLoad = Math.max(0, this.assignedLoad - suitcases);
    }

    public int availableSpace() {
        return maxCapacity - assignedLoad;
    }

    public int absoluteDepartureMinute() {
        return dayOffset * 1440 + departureMinute;
    }

    public int absoluteArrivalMinute() {
        return dayOffset * 1440 + arrivalMinute;
    }

    public String getFlightId() { return flightId; }
    public String getOriginCode() { return originCode; }
    public String getDestCode() { return destCode; }
    public int getDepartureMinute() { return departureMinute; }
    public int getArrivalMinute() { return arrivalMinute; }
    public int getMaxCapacity() { return maxCapacity; }
    public int getAssignedLoad() { return assignedLoad; }
    public int getDayOffset() { return dayOffset; }

    public void resetLoad() {
        this.assignedLoad = 0;
    }

    @Override
    public String toString() {
        return String.format("Flight[%s -> %s | Day %d | Dep: %s | Arr: %s | Load: %d/%d]",
                originCode, destCode, dayOffset,
                minutesToHHMM(departureMinute),
                minutesToHHMM(arrivalMinute),
                assignedLoad, maxCapacity);
    }

    public static String minutesToHHMM(int minutes) {
        int h = (minutes / 60) % 24;
        int m = minutes % 60;
        return String.format("%02d:%02d", h, m);
    }
}
