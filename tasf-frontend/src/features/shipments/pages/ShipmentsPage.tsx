import { useEffect, useMemo, useState, type FormEvent } from "react";
import {
  createShipmentBatchRequest,
  createShipmentRequest,
  getAirportsRequest,
  type ShipmentBatchResult,
  type ShipmentCreatePayload,
  type ShipmentRecord,
} from "../../../api/simulationApi";
import { Navbar } from "../../../shared/components/Navbar/Navbar";
import type { Airport } from "../../simulation/types";

function emptyForm(defaultAirportCode = ""): ShipmentCreatePayload {
  return {
    originAirportCode: defaultAirportCode,
    destinationAirportCode: defaultAirportCode,
    departureDate: "",
    baggageCount: 1,
    shipmentId: "",
  };
}

type ShipmentBatchForm = {
  originAirportCode: string;
  file: File | null;
};

export function ShipmentsPage() {
  const [airports, setAirports] = useState<Airport[]>([]);
  const [form, setForm] = useState<ShipmentCreatePayload | null>(null);
  const [batchForm, setBatchForm] = useState<ShipmentBatchForm | null>(null);
  const [created, setCreated] = useState<ShipmentRecord | null>(null);
  const [createdBatch, setCreatedBatch] = useState<ShipmentBatchResult | null>(null);
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState("");
  const [modalError, setModalError] = useState("");

  useEffect(() => {
    let ignore = false;

    void getAirportsRequest()
      .then((payload) => {
        if (!ignore) setAirports(payload);
      })
      .catch((err) => {
        if (!ignore) {
          setError(err instanceof Error ? err.message : "No se pudieron cargar aeropuertos.");
        }
      })
      .finally(() => {
        if (!ignore) setLoading(false);
      });

    return () => {
      ignore = true;
    };
  }, []);

  const airportOptions = useMemo(
    () => [...airports].sort((a, b) => a.code.localeCompare(b.code)),
    [airports]
  );

  const openCreator = () => {
    setCreated(null);
    setCreatedBatch(null);
    setModalError("");
    setForm(emptyForm(airportOptions[0]?.code || ""));
  };

  const openBatchCreator = () => {
    setCreated(null);
    setCreatedBatch(null);
    setModalError("");
    setBatchForm({ originAirportCode: airportOptions[0]?.code || "", file: null });
  };

  const closeCreator = () => {
    if (saving) return;
    setForm(null);
    setBatchForm(null);
    setModalError("");
  };

  const updateForm = <K extends keyof ShipmentCreatePayload>(
    key: K,
    value: ShipmentCreatePayload[K]
  ) => {
    setForm((current) => (current ? { ...current, [key]: value } : current));
  };

  const saveShipment = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (!form) return;

    setSaving(true);
    setModalError("");

    try {
      const createdShipment = await createShipmentRequest({
        ...form,
        originAirportCode: form.originAirportCode.toUpperCase(),
        destinationAirportCode: form.destinationAirportCode.toUpperCase(),
        shipmentId: form.shipmentId.padStart(9, "0"),
      });
      setCreated(createdShipment);
      setForm(null);
    } catch (err) {
      setModalError(err instanceof Error ? err.message : "No se pudo registrar el envio.");
    } finally {
      setSaving(false);
    }
  };

  const saveShipmentBatch = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (!batchForm) return;
    if (!batchForm.file) {
      setModalError("Selecciona un archivo de texto.");
      return;
    }

    setSaving(true);
    setModalError("");

    try {
      const fileContent = await batchForm.file.text();
      const result = await createShipmentBatchRequest({
        originAirportCode: batchForm.originAirportCode.toUpperCase(),
        fileContent,
      });
      setCreatedBatch(result);
      setBatchForm(null);
    } catch (err) {
      setModalError(err instanceof Error ? err.message : "No se pudo registrar el lote.");
    } finally {
      setSaving(false);
    }
  };

  return (
    <div className="app-shell">
      <Navbar />

      <main className="dashboard-workspace shipments-workspace">
        <section className="dashboard-heading">
          <div>
            <h1>Envíos</h1>
            <p>Registro manual de envios en la base de datos.</p>
          </div>
          <div className="toolbar-actions">
            <button className="ghost" onClick={openBatchCreator} disabled={loading || !airportOptions.length}>
              Nuevo lote
            </button>
            <button className="primary" onClick={openCreator} disabled={loading || !airportOptions.length}>
              Nuevo
            </button>
          </div>
        </section>

        {error && <div className="error">{error}</div>}

        <section className="panel section shipments-panel">
          {createdBatch ? (
            <div className="success">
              {`Lote registrado: ${createdBatch.inserted} envios insertados de ${createdBatch.parsed} lineas validas.`}
              {createdBatch.skipped > 0 ? ` Duplicados omitidos: ${createdBatch.skipped}.` : ""}
            </div>
          ) : created ? (
            <div className="success">
              {`Envio registrado: ${created.shipment_code} (${created.baggage_count} maletas).`}
            </div>
          ) : (
            <div className="empty-state">
              {loading ? "Cargando aeropuertos..." : "Usa Nuevo para registrar un envio."}
            </div>
          )}
        </section>
      </main>

      {form && (
        <div className="modal-backdrop" role="presentation" onMouseDown={closeCreator}>
          <div
            className="airport-modal"
            role="dialog"
            aria-modal="true"
            aria-labelledby="shipment-editor-title"
            onMouseDown={(event) => event.stopPropagation()}
          >
            <div className="modal-header">
              <div>
                <h2 id="shipment-editor-title">Nuevo envío</h2>
                <span>Se guardara con status REGISTERED</span>
              </div>
              <button className="icon-button" type="button" onClick={closeCreator} disabled={saving}>
                x
              </button>
            </div>

            <form className="airport-form" onSubmit={saveShipment}>
              <div className="field">
                <label>Aeropuerto origen</label>
                <select
                  value={form.originAirportCode}
                  onChange={(event) => updateForm("originAirportCode", event.target.value)}
                  required
                >
                  {airportOptions.map((airport) => (
                    <option key={airport.code} value={airport.code}>
                      {airport.code}
                    </option>
                  ))}
                </select>
              </div>
              <div className="field">
                <label>Aeropuerto destino</label>
                <select
                  value={form.destinationAirportCode}
                  onChange={(event) => updateForm("destinationAirportCode", event.target.value)}
                  required
                >
                  {airportOptions.map((airport) => (
                    <option key={airport.code} value={airport.code}>
                      {airport.code}
                    </option>
                  ))}
                </select>
              </div>
              <div className="field">
                <label>Fecha de salida</label>
                <input
                  type="datetime-local"
                  step="1"
                  value={form.departureDate}
                  onChange={(event) => updateForm("departureDate", event.target.value)}
                  required
                />
              </div>
              <div className="field">
                <label>Cantidad de maletas</label>
                <input
                  type="number"
                  min="1"
                  step="1"
                  value={form.baggageCount}
                  onChange={(event) => updateForm("baggageCount", Number(event.target.value))}
                  required
                />
              </div>
              <div className="field">
                <label>ID del envío</label>
                <input
                  inputMode="numeric"
                  pattern="[0-9]{1,9}"
                  maxLength={9}
                  value={form.shipmentId}
                  onChange={(event) =>
                    updateForm("shipmentId", event.target.value.replace(/\D/g, "").slice(0, 9))
                  }
                  placeholder="000000001"
                  required
                />
              </div>

              {modalError && <div className="error modal-error">{modalError}</div>}

              <div className="modal-actions">
                <button className="ghost" type="button" onClick={closeCreator} disabled={saving}>
                  Cancelar
                </button>
                <button className="primary" type="submit" disabled={saving}>
                  {saving ? "Registrando..." : "Registrar envío"}
                </button>
              </div>
            </form>
          </div>
        </div>
      )}

      {batchForm && (
        <div className="modal-backdrop" role="presentation" onMouseDown={closeCreator}>
          <div
            className="airport-modal"
            role="dialog"
            aria-modal="true"
            aria-labelledby="shipment-batch-title"
            onMouseDown={(event) => event.stopPropagation()}
          >
            <div className="modal-header">
              <div>
                <h2 id="shipment-batch-title">Nuevo lote</h2>
                <span>Se guardara cada linea con status REGISTERED</span>
              </div>
              <button className="icon-button" type="button" onClick={closeCreator} disabled={saving}>
                x
              </button>
            </div>

            <form className="airport-form" onSubmit={saveShipmentBatch}>
              <div className="field">
                <label>Aeropuerto origen</label>
                <select
                  value={batchForm.originAirportCode}
                  onChange={(event) =>
                    setBatchForm((current) =>
                      current ? { ...current, originAirportCode: event.target.value } : current
                    )
                  }
                  required
                >
                  {airportOptions.map((airport) => (
                    <option key={airport.code} value={airport.code}>
                      {airport.code}
                    </option>
                  ))}
                </select>
              </div>
              <div className="field">
                <label>Archivo de texto</label>
                <input
                  type="file"
                  accept=".txt,text/plain"
                  onChange={(event) =>
                    setBatchForm((current) =>
                      current ? { ...current, file: event.target.files?.[0] ?? null } : current
                    )
                  }
                  required
                />
              </div>

              {modalError && <div className="error modal-error">{modalError}</div>}

              <div className="modal-actions">
                <button className="ghost" type="button" onClick={closeCreator} disabled={saving}>
                  Cancelar
                </button>
                <button className="primary" type="submit" disabled={saving}>
                  {saving ? "Registrando..." : "Registrar lote"}
                </button>
              </div>
            </form>
          </div>
        </div>
      )}
    </div>
  );
}
