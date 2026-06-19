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
import { StatsPage } from "../features/stats/pages/StatsPage";
import { UploadPage } from "@/features/upload/pages/UploadPage";
import { BatchSimulationCoordinator } from "../features/simulation/components/BatchSimulationCoordinator";


export default function AppRouter() {
  return (
    <BrowserRouter>

      <BatchSimulationCoordinator />

      <Routes>

        <Route path="/" element={<SimulationPage />}/>
        <Route path="/operations" element={<OperationsPage />}/>
        <Route path="/baggage" element={<BaggagePage />}/>
        <Route path="/airports" element={<AirportsPage />}/>
        <Route path="/flights" element={<FlightsPage />}/>
        <Route path="/stats" element={<StatsPage />}/>
        <Route path="/upload" element={<UploadPage />}/>
      </Routes>

    </BrowserRouter>
  );
}
