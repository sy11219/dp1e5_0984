import {
  BrowserRouter,
  Routes,
  Route,
} from "react-router-dom";

import { SimulationPage } from "../features/simulation/pages/SimulationPage";
import { OperationsPage } from "../features/operations/pages/OperationsPage";
import { BaggagePage } from "../features/baggage/pages/BaggagePage";
import { AirportsPage } from "../features/airports/pages/AirportsPage";
import { FlightsPage } from "../features/flights/pages/FlightsPage";
import { ShipmentsPage } from "../features/shipments/pages/ShipmentsPage";
import { StatsPage } from "../features/stats/pages/StatsPage";
import { BatchSimulationCoordinator } from "../features/simulation/components/BatchSimulationCoordinator";
import { CollapsePage } from "@/features/collapse/pages/CollapsePage";


export default function AppRouter() {
  return (
    <BrowserRouter>

      <BatchSimulationCoordinator />

      <Routes>

        <Route path="/" element={<SimulationPage />}/>
        <Route path="/operations" element={<OperationsPage />}/>
        <Route path="/baggage" element={<BaggagePage />}/>
        <Route path="/collapse" element={<CollapsePage />}/>
        <Route path="/airports" element={<AirportsPage />}/>
        <Route path="/flights" element={<FlightsPage />}/>
        <Route path="/shipments" element={<ShipmentsPage />}/>
        <Route path="/stats" element={<StatsPage />}/>
      </Routes>

    </BrowserRouter>
  );
}
