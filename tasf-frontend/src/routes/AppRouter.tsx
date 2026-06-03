import {
  BrowserRouter,
  Routes,
  Route,
} from "react-router-dom";

import { SimulationPage } from "../features/simulation/pages/SimulationPage";
import { OperationsPage } from "../features/operations/pages/OperationsPage";
import { BaggagePage } from "../features/baggage/pages/BaggagePage";
import { StatsPage } from "../features/stats/pages/StatsPage";
import { UploadPage } from "@/features/upload/pages/UploadPage";


export default function AppRouter() {
  return (
    <BrowserRouter>

      <Routes>

        <Route path="/" element={<SimulationPage />}/>
        <Route path="/operations" element={<OperationsPage />}/>
        <Route path="/baggage" element={<BaggagePage />}/>
        <Route path="/stats" element={<StatsPage />}/>
        <Route path="/upload" element={<UploadPage />}/>
      </Routes>

    </BrowserRouter>
  );
}