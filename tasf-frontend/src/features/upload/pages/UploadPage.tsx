import { useState } from "react";
import { Navbar } from "../../../shared/components/Navbar/Navbar";
import { uploadRequest } from "@/api/uploadApi";
import "./UploadPage.css";

export const UploadPage = () => {
  const [flightFile, setFlightFile] = useState<File | null>(null);
  const [airportFile, setAirportFile] = useState<File | null>(null);
  const [shipmentFiles, setShipmentFiles] = useState<File[]>([]);
  const [lastUpdate, setLastUpdate] = useState<Date | null>(null);

  const handleUpdate = async () => {
    if (!flightFile && !airportFile && shipmentFiles.length === 0) {
      alert("Selecciona al menos un archivo para subir.");
      return;
    }
    
    try {
      await uploadRequest(flightFile, airportFile, shipmentFiles);
      setLastUpdate(new Date());
      //alert("Archivos subidos exitosamente");
      setTimeout(() => {
        alert("Archivos subidos exitosamente");
      }, 2000);
    } catch (error) {
      alert("Error al subir archivos: " + error);
    }
  };

  return (
    <div className="app-shell">
      <Navbar />

      <main className="dashboard-workspace">
        <section className="dashboard-heading">
          <div>
            <h1>Carga de archivos</h1>
            <p>Sube la información inicial de planes de vuelo, aeropuertos y envíos.</p>
          </div>
        </section>

        <section className="dashboard-grid">
          <div className="panel section upload-panel">
            <h2>Planes de vuelo</h2>
            <input
              type="file"
              accept=".txt"
              onChange={(e) => setFlightFile(e.target.files?.[0] ?? null)}
            />
            {flightFile && (
                <div className="file-row">
                  <button className="file-remove" onClick={() => setFlightFile(null)}>
                    Quitar
                  </button>
                </div>
              )}
          </div>

          <div className="panel section upload-panel">
            <h2>Aeropuertos</h2>
            <input
              type="file"
              accept=".txt"
              onChange={(e) => setAirportFile(e.target.files?.[0] ?? null)}
            />
            {airportFile && (
              <div className="file-row">
                <button className="file-remove" onClick={() => setAirportFile(null)}>
                  Quitar
                </button>
              </div>
            )}
          </div>

          <div className="panel section upload-panel">
            <h2>Envíos (Formato: _envios_XXXX.txt)</h2>
            <input
              type="file"
              accept=".txt"
              multiple
              onChange={(e) => {
                const files = Array.from(e.target.files ?? []);
                const regex = /^_envios_\d{4}_\.txt$/i;

                // Si alguno no cumple, limpiar todo
                if (files.some((file) => !regex.test(file.name))) {
                  alert("Formato inválido. Usa _envios_1234_.txt");
                  e.target.value = ""; // resetear input
                  return;
                }

                setShipmentFiles(files);
              }}
            />
            {shipmentFiles.length > 0 && (
              <ul>
                {shipmentFiles.map((file, index) => (
                  <li key={file.name} className="file-row">
                    <span>Archivo {index + 1}: {file.name}</span>
                    <button
                      className="file-remove"
                      onClick={() =>
                        setShipmentFiles(shipmentFiles.filter((_, i) => i !== index))
                      }
                    >
                      Quitar
                    </button>
                  </li>
                ))}
              </ul>
            )}
          </div>
        </section>

        <section className="panel section update-panel">
          <button className="update-button" onClick={handleUpdate}>
            Actualizar
          </button>
          <p>
            Última actualización:{" "}
            {lastUpdate ? lastUpdate.toLocaleString("es-PE") : "Nunca"}
          </p>
        </section>
      </main>
    </div>
  );
};
