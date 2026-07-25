import { useEffect } from "react";
import { getAirportsRequest } from "./api/simulationApi";
import { assignAirportTimeFromSystemTimeZone } from "./features/simulation/utils/assignedAirportTime";
import AppRouter from "./routes/AppRouter";

export default function App() {
  useEffect(() => {
    let cancelled = false;
    void getAirportsRequest()
      .then((airports) => {
        if (!cancelled) assignAirportTimeFromSystemTimeZone(airports);
      })
      .catch(() => {
        // Si no se puede leer el catálogo, no se asigna aeropuerto automáticamente.
      });

    return () => {
      cancelled = true;
    };
  }, []);

  return <AppRouter />;
}
