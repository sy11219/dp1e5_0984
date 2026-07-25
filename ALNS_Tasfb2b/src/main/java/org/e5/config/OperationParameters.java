package org.e5.config;

/**
 * Parámetros operativos compartidos por el planificador y la simulación.
 *
 * Pueden ajustarse con variables de entorno sin tocar código:
 * - TASF_CONNECTION_WAIT_MINUTES: espera minima en escala.
 * - TASF_FINAL_PICKUP_WAIT_MINUTES: tiempo hasta retiro en destino final.
 */
public final class OperationParameters {

    private static final int DEFAULT_CONNECTION_WAIT_MINUTES = 10;
    private static final int DEFAULT_FINAL_PICKUP_WAIT_MINUTES = 15;

    public static final int CONNECTION_WAIT_MINUTES = readPositiveInt(
            "TASF_CONNECTION_WAIT_MINUTES",
            DEFAULT_CONNECTION_WAIT_MINUTES
    );

    public static final int FINAL_PICKUP_WAIT_MINUTES = readPositiveInt(
            "TASF_FINAL_PICKUP_WAIT_MINUTES",
            DEFAULT_FINAL_PICKUP_WAIT_MINUTES
    );

    private OperationParameters() {
    }

    private static int readPositiveInt(String envName, int defaultValue) {
        String raw = System.getenv(envName);
        if (raw == null || raw.isBlank()) return defaultValue;
        try {
            int value = Integer.parseInt(raw.trim());
            return value > 0 ? value : defaultValue;
        } catch (NumberFormatException ignored) {
            return defaultValue;
        }
    }
}
