import { useEffect, useMemo, useState, type FormEvent } from "react";
import {
  createShipmentBatchRequest,
  createShipmentRequest,
  getAirportsRequest,
  getShipmentsRequest,
  type ShipmentBatchResult,
  type ShipmentCreatePayload,
  type ShipmentListRecord,
  type ShipmentRecord,
} from "../../../api/simulationApi";
import { Navbar } from "../../../shared/components/Navbar/Navbar";
import type { Airport } from "../../simulation/types";
import { useAssignedAirportTime } from "../../simulation/utils/assignedAirportTime";

const PAGE_SIZE = 12;

function currentDateTimeLocalValue() {
  const now = new Date();
  const local = new Date(now.getTime() - now.getTimezoneOffset() * 60_000);
  return local.toISOString().slice(0, 19);
}

function currentDateValue() {
  return currentDateTimeLocalValue().slice(0, 10);
}

function formatShipmentDate(value: string) {
  if (!value) return "--";
  if (/^\d{8}\s\d{2}:\d{2}$/.test(value)) {
    return `${value.slice(0, 4)}-${value.slice(4, 6)}-${value.slice(6, 8)} ${value.slice(9)}`;
  }
  return value.replace("T", " ").replace("Z", " UTC");
}

function emptyForm(originAirportCode = "", destinationAirportCode = ""): ShipmentCreatePayload {
  return {
    originAirportCode,
    destinationAirportCode,
    departureDate: currentDateTimeLocalValue(),
    baggageCount: 1,
    clientId: "",
  };
}

type ShipmentBatchForm = {
  originAirportCode: string;
  file: File | null;
};

export function ShipmentsPage() {
  const assignedAirportTime = useAssignedAirportTime();
  const [airports, setAirports] = useState<Airport[]>([]);
  const [shipments, setShipments] = useState<ShipmentListRecord[]>([]);
  const [form, setForm] = useState<ShipmentCreatePayload | null>(null);
  const [batchForm, setBatchForm] = useState<ShipmentBatchForm | null>(null);
  const [created, setCreated] = useState<ShipmentRecord | null>(null);
  const [createdBatch, setCreatedBatch] = useState<ShipmentBatchResult | null>(null);
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState("");
  const [modalError, setModalError] = useState("");
  const [search, setSearch] = useState("");
  const [page, setPage] = useState(1);
  const [listDate] = useState(currentDateValue);

  const loadPageData = async () => {
    setLoading(true);
    setError("");

    try {
      const [airportPayload, shipmentPayload] = await Promise.all([
        getAirportsRequest(),
        getShipmentsRequest(listDate),
      ]);
      setAirports(airportPayload);
      setShipments(shipmentPayload);
      setPage(1);
    } catch (err) {
      setError(err instanceof Error ? err.message : "No se pudieron cargar los envios.");
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    void loadPageData();
  }, [listDate]);

  useEffect(() => {
    setPage(1);
  }, [search, assignedAirportTime?.code]);

  const airportOptions = useMemo(
    () => [...airports].sort((a, b) => a.code.localeCompare(b.code)),
    [airports]
  );

  const filtered = useMemo(() => {
    const query = search.trim().toLowerCase();
    const assignedCode = assignedAirportTime?.code;

    return shipments
      .filter((shipment) => {
        if (!assignedCode) return true;
        return (
          shipment.origin_airport_code === assignedCode ||
          shipment.destination_airport_code === assignedCode
        );
      })
      .filter((shipment) => {
        if (!query) return true;
        return [
          shipment.shipment_code,
          shipment.origin_airport_code,
          shipment.destination_airport_code,
          shipment.baggage_count,
          shipment.shipment_date,
        ].some((value) => String(value).toLowerCase().includes(query));
      })
      .sort(
        (a, b) =>
          a.shipment_date.localeCompare(b.shipment_date) ||
          a.origin_airport_code.localeCompare(b.origin_airport_code) ||
          a.shipment_code.localeCompare(b.shipment_code)
      );
  }, [assignedAirportTime?.code, shipments, search]);

  const pageCount = Math.max(1, Math.ceil(filtered.length / PAGE_SIZE));
  const safePage = Math.min(page, pageCount);
  const visible = filtered.slice((safePage - 1) * PAGE_SIZE, safePage * PAGE_SIZE);
  const totalBags = shipments.reduce((sum, shipment) => sum + shipment.baggage_count, 0);

  const openCreator = () => {
    if (!assignedAirportTime?.code) return;
    const defaultDestination =
      airportOptions.find((airport) => airport.code !== assignedAirportTime.code)?.code ||
      assignedAirportTime.code;
    setCreated(null);
    setCreatedBatch(null);
    setModalError("");
    setForm(emptyForm(assignedAirportTime.code, defaultDestination));
  };

  const openBatchCreator = () => {
    if (!assignedAirportTime?.code) return;
    setCreated(null);
    setCreatedBatch(null);
    setModalError("");
    setBatchForm({ originAirportCode: assignedAirportTime.code, file: null });
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
        originAirportCode: (assignedAirportTime?.code || form.originAirportCode).toUpperCase(),
        destinationAirportCode: form.destinationAirportCode.toUpperCase(),
        departureDate: currentDateTimeLocalValue(),
        clientId: form.clientId,
      });
      setCreated(createdShipment);
      setForm(null);
      await loadPageData();
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
        originAirportCode: (assignedAirportTime?.code || batchForm.originAirportCode).toUpperCase(),
        fileContent,
      });
      setCreatedBatch(result);
      setBatchForm(null);
      await loadPageData();
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
            <button
              className="ghost"
              onClick={openBatchCreator}
              disabled={loading || !airportOptions.length || !assignedAirportTime?.code}
            >
              Nuevo lote
            </button>
            <button
              className="primary"
              onClick={openCreator}
              disabled={loading || !airportOptions.length || !assignedAirportTime?.code}
            >
              Nuevo
            </button>
          </div>
        </section>

        {error && <div className="error">{error}</div>}

        <section className="dashboard-grid">
          <div className="panel section metric-panel">
            <span>Total</span>
            <strong>{shipments.length}</strong>
            <small>envios restantes de hoy</small>
          </div>
          <div className="panel section metric-panel">
            <span>Maletas</span>
            <strong>{totalBags.toLocaleString("es-PE")}</strong>
            <small>desde ahora hasta fin del día</small>
          </div>
        </section>

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
            <div className="empty-state">{`Mostrando envios desde ahora hasta el final del ${listDate}.`}</div>
          )}
        </section>

        <section className="panel section shipments-panel">
          {assignedAirportTime && (
            <div className="success" style={{ marginBottom: "1rem" }}>
              {`Mostrando envios con origen o destino en ${assignedAirportTime.code} - ${
                assignedAirportTime.city || "aeropuerto asignado"
              }.`}
            </div>
          )}

          <div className="airports-toolbar">
            <div className="field">
              <label>Buscar</label>
              <input
                type="search"
                placeholder="Codigo, origen o destino"
                value={search}
                onChange={(event) => setSearch(event.target.value)}
              />
            </div>
          </div>

          <div className="airports-table-wrap">
            <table className="data-table airports-data-table">
              <thead>
                <tr>
                  <th>CODIGO</th>
                  <th>AEROPUERTO_ORIGEN</th>
                  <th>AEROPUERTO_DESTINO</th>
                  <th>MALETAS</th>
                  <th>FECHA_ENVIO</th>
                </tr>
              </thead>
              <tbody>
                {visible.map((shipment, index) => (
                  <tr key={`${shipment.source}-${shipment.shipment_code}-${index}`}>
                    <td>
                      <strong>{shipment.shipment_code}</strong>
                    </td>
                    <td>{shipment.origin_airport_code}</td>
                    <td>{shipment.destination_airport_code}</td>
                    <td>{shipment.baggage_count.toLocaleString("es-PE")}</td>
                    <td>{formatShipmentDate(shipment.shipment_date)}</td>
                  </tr>
                ))}
              </tbody>
            </table>

            {!visible.length && (
              <div className="empty-state">
                {loading ? "Cargando envios..." : "No se encontraron envios pendientes para hoy."}
              </div>
            )}
          </div>

          <div className="pagination">
            <span>
              {filtered.length
                ? `${(safePage - 1) * PAGE_SIZE + 1}-${Math.min(
                    safePage * PAGE_SIZE,
                    filtered.length
                  )} de ${filtered.length}`
                : "0 de 0"}
            </span>
            <div>
              <button
                className="ghost"
                onClick={() => setPage((current) => Math.max(1, current - 1))}
                disabled={safePage === 1}
              >
                Anterior
              </button>
              <strong>{`${safePage} / ${pageCount}`}</strong>
              <button
                className="ghost"
                onClick={() => setPage((current) => Math.min(pageCount, current + 1))}
                disabled={safePage === pageCount}
              >
                Siguiente
              </button>
            </div>
          </div>
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
              <div className="empty-state">
                {`Origen: ${assignedAirportTime?.code || form.originAirportCode} - ${
                  assignedAirportTime?.city || "aeropuerto asignado"
                }`}
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
                <label>ID del cliente</label>
                <input
                  inputMode="numeric"
                  pattern="[0-9]{7}"
                  maxLength={7}
                  value={form.clientId}
                  onChange={(event) =>
                    updateForm("clientId", event.target.value.replace(/\D/g, "").slice(0, 7))
                  }
                  placeholder="0005296"
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
              <div className="empty-state">
                {`Origen: ${assignedAirportTime?.code || batchForm.originAirportCode} - ${
                  assignedAirportTime?.city || "aeropuerto asignado"
                }`}
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
