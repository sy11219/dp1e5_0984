package org.e5.web;

import org.e5.model.Airport;
import org.e5.model.Flight;
import org.e5.parser.AirportParser;
import org.e5.parser.FlightPlanParser;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Loads the shared operational catalog from DB and gives each scenario its own
 * mutable runtime copy. This keeps master data shared while preventing
 * simulation loads, cancellations, or airport mutations from leaking between
 * SIMULACION_LOTES and TIEMPO_REAL.
 */
public class RuntimeCatalogService {

    private static final long DEFAULT_CACHE_MS = 30_000L;
    private static final long CACHE_MS = readPositiveLong("TASF_CATALOG_CACHE_MS", DEFAULT_CACHE_MS);

    private CatalogSnapshot cachedBase;
    private long cachedAtMs;

    /** Fuerza a que la próxima sesión lea el catálogo compartido actualizado. */
    public synchronized void invalidate() {
        cachedBase = null;
        cachedAtMs = 0L;
    }

    public synchronized RuntimeCatalog loadRuntimeCatalog(String startDate, int simulationDays) throws Exception {
        CatalogSnapshot base = loadBaseCatalog(startDate);

        List<Airport> airports = new ArrayList<>(base.airports().size());
        Map<String, Airport> airportMap = new LinkedHashMap<>();
        for (Airport airport : base.airports()) {
            Airport copy = copyAirport(airport);
            airports.add(copy);
            airportMap.put(copy.getCode(), copy);
        }

        List<Flight> flights = new ArrayList<>(base.flightTemplates().size() * Math.max(0, simulationDays));
        for (int dayOffset = 0; dayOffset < simulationDays; dayOffset++) {
            for (Flight template : base.flightTemplates()) {
                flights.add(new Flight(
                        template.getFlightId(),
                        template.getOriginCode(),
                        template.getDestCode(),
                        template.getDepartureMinute(),
                        template.getArrivalMinute(),
                        template.getMaxCapacity(),
                        dayOffset
                ));
            }
        }

        return new RuntimeCatalog(airports, airportMap, flights);
    }

    public synchronized void invalidateCache() {
        cachedBase = null;
        cachedAtMs = 0;
    }

    private CatalogSnapshot loadBaseCatalog(String startDate) throws Exception {
        long now = System.currentTimeMillis();
        if (cachedBase != null && now - cachedAtMs <= CACHE_MS) {
            return cachedBase;
        }

        AirportParser airportParser = new AirportParser();
        List<Airport> airports = airportParser.parse();
        Map<String, Airport> airportMap = new LinkedHashMap<>();
        for (Airport airport : airports) {
            airportMap.put(airport.getCode(), airport);
        }

        FlightPlanParser flightParser = new FlightPlanParser();
        List<Flight> flightTemplates = flightParser.parseScheduledFromDatabase(startDate, 1, airportMap);

        cachedBase = new CatalogSnapshot(copyAirports(airports), copyFlights(flightTemplates));
        cachedAtMs = now;
        return cachedBase;
    }

    private static List<Airport> copyAirports(List<Airport> airports) {
        List<Airport> copies = new ArrayList<>(airports.size());
        for (Airport airport : airports) {
            copies.add(copyAirport(airport));
        }
        return copies;
    }

    private static List<Flight> copyFlights(List<Flight> flights) {
        List<Flight> copies = new ArrayList<>(flights.size());
        for (Flight flight : flights) {
            copies.add(new Flight(
                    flight.getFlightId(),
                    flight.getOriginCode(),
                    flight.getDestCode(),
                    flight.getDepartureMinute(),
                    flight.getArrivalMinute(),
                    flight.getMaxCapacity(),
                    0
            ));
        }
        return copies;
    }

    private static Airport copyAirport(Airport airport) {
        return new Airport(
                airport.getCode(),
                airport.getCity(),
                airport.getCountry(),
                airport.getContinent(),
                airport.getMaxCapacity(),
                airport.getLatitude(),
                airport.getLongitude(),
                airport.getGmtOffset()
        );
    }

    private static long readPositiveLong(String name, long defaultValue) {
        String raw = System.getenv(name);
        if (raw == null || raw.isBlank()) return defaultValue;
        try {
            long parsed = Long.parseLong(raw.trim());
            return parsed > 0 ? parsed : defaultValue;
        } catch (NumberFormatException ignored) {
            return defaultValue;
        }
    }

    private record CatalogSnapshot(List<Airport> airports, List<Flight> flightTemplates) {
    }

    public record RuntimeCatalog(
            List<Airport> airports,
            Map<String, Airport> airportMap,
            List<Flight> flights
    ) {
    }
}
