import { useEffect, useMemo, useState, type FormEvent } from "react";
import {
  createFlightPlanRequest,
  getAirportsRequest,
  getFlightPlansRequest,
  updateFlightPlanRequest,
  type FlightPlanRecord,
  type FlightPlanUpdatePayload,
} from "../../../api/simulationApi";
import { Navbar } from "../../../shared/components/Navbar/Navbar";
import type { Airport } from "../../simulation/types";
import { useAssignedAirportTime } from "../../simulation/utils/assignedAirportTime";

const PAGE_SIZE = 12;
type EditorMode = "create" | "edit";

function formatDateTime(value: string) {
  if (!value) return "--";
  return value.replace("T", " ").replace("Z", " UTC");
}

function statusLabel(flight: FlightPlanRecord) {
  return flight.flightStatus || flight.scheduleStatus || "SCHEDULED";
}

function isCanceled(flight: FlightPlanRecord) {
  return statusLabel(flight).toUpperCase() === "CANCELED";
}

function toDateTimeInput(value: string) {
  if (!value) return "";
  return value.replace("Z", "").slice(0, 19);
}

function flightToForm(flight: FlightPlanRecord): FlightPlanUpdatePayload {
  return {
    originAirportCode: flight.origin,
    destinationAirportCode: flight.destination,
    departureTimeLocal: toDateTimeInput(flight.departure_time_local),
    arrivalTimeLocal: toDateTimeInput(flight.arrival_time_local),
    departureTimeUtc: toDateTimeInput(flight.departure_time_utc),
    arrivalTimeUtc: toDateTimeInput(flight.arrival_time_utc),
    capacity: flight.capacity || 1,
    status: isCanceled(flight) ? "CANCELED" : "SCHEDULED",
  };
}

function emptyFlightForm(defaultAirportCode = ""): FlightPlanUpdatePayload {
  return {
    originAirportCode: defaultAirportCode,
    destinationAirportCode: defaultAirportCode,
    departureTimeLocal: "",
    arrivalTimeLocal: "",
    departureTimeUtc: "",
    arrivalTimeUtc: "",
    capacity: 1,
    status: "SCHEDULED",
  };
}

export function FlightsPage() {
  const assignedAirportTime = useAssignedAirportTime();
  const [flights, setFlights] = useState<FlightPlanRecord[]>([]);
  const [airports, setAirports] = useState<Airport[]>([]);
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState("");
  const [modalError, setModalError] = useState("");
  const [search, setSearch] = useState("");
  const [status, setStatus] = useState("ALL");
  const [page, setPage] = useState(1);
  const [selectedFlight, setSelectedFlight] = useState<FlightPlanRecord | null>(null);
  const [editorMode, setEditorMode] = useState<EditorMode | null>(null);
  const [form, setForm] = useState<FlightPlanUpdatePayload | null>(null);

  const loadFlights = async () => {
    setLoading(true);
    setError("");

    try {
      const [flightPayload, airportPayload] = await Promise.all([
        getFlightPlansRequest(),
        getAirportsRequest(),
      ]);
      setFlights(flightPayload);
      setAirports(airportPayload);
      setPage(1);
    } catch (err) {
      setError(err instanceof Error ? err.message : "No se pudieron cargar los vuelos.");
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    void loadFlights();
  }, []);

  useEffect(() => {
    setPage(1);
  }, [search, status, assignedAirportTime?.code]);

  const statuses = useMemo(
    () => Array.from(new Set(flights.map(statusLabel).filter(Boolean))).sort(),
    [flights]
  );

  const filtered = useMemo(() => {
    const query = search.trim().toLowerCase();
    const assignedCode = assignedAirportTime?.code;

    return flights
      .filter((flight) => {
        if (!assignedCode) return true;
        return flight.origin === assignedCode || flight.destination === assignedCode;
      })
      .filter((flight) => {
        if (!query) return true;
        return [
          flight.flight_code,
          flight.origin,
          flight.destination,
          flight.origin_airport_id,
          flight.destination_airport_id,
          flight.departure_time_local,
          flight.arrival_time_local,
          flight.departure_time_utc,
          flight.arrival_time_utc,
          statusLabel(flight),
        ].some((value) => String(value).toLowerCase().includes(query));
      })
      .filter((flight) => status === "ALL" || statusLabel(flight) === status)
      .sort(
        (a, b) =>
          a.departure_time_utc.localeCompare(b.departure_time_utc) ||
          a.flight_code.localeCompare(b.flight_code)
      );
  }, [assignedAirportTime?.code, flights, search, status]);

  const pageCount = Math.max(1, Math.ceil(filtered.length / PAGE_SIZE));
  const safePage = Math.min(page, pageCount);
  const visible = filtered.slice((safePage - 1) * PAGE_SIZE, safePage * PAGE_SIZE);
  const canceledCount = flights.filter(isCanceled).length;
  const scheduledCount = flights.length - canceledCount;

  const airportOptions = useMemo(
    () => [...airports].sort((a, b) => a.code.localeCompare(b.code)),
    [airports]
  );

  const openEditor = (flight: FlightPlanRecord) => {
    setSelectedFlight(flight);
    setEditorMode("edit");
    setForm(flightToForm(flight));
    setModalError("");
  };

  const openCreator = () => {
    const defaultAirportCode = assignedAirportTime?.code || "";
    const defaultDestinationCode = airportOptions[0]?.code || "";
    setSelectedFlight(null);
    setEditorMode("create");
    setForm({
      ...emptyFlightForm(defaultAirportCode),
      destinationAirportCode: defaultDestinationCode,
    });
    setModalError("");
  };

  const closeEditor = () => {
    if (saving) return;
    setSelectedFlight(null);
    setEditorMode(null);
    setForm(null);
    setModalError("");
  };

  const updateForm = <K extends keyof FlightPlanUpdatePayload>(
    key: K,
    value: FlightPlanUpdatePayload[K]
  ) => {
    setForm((current) => (current ? { ...current, [key]: value } : current));
  };

  const saveFlight = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (!editorMode || !form) return;

    setSaving(true);
    setModalError("");

    try {
      if (editorMode === "create") {
        const originAirportCode = assignedAirportTime?.code || form.originAirportCode;
        const created = await createFlightPlanRequest({
          originAirportCode,
          destinationAirportCode: form.destinationAirportCode,
          departureTimeLocal: form.departureTimeLocal,
          arrivalTimeLocal: form.arrivalTimeLocal,
          capacity: form.capacity,
        });
        setFlights((current) => [...current, created]);
      } else if (selectedFlight) {
        const updated = await updateFlightPlanRequest(selectedFlight.flight_code, form);
        setFlights((current) =>
          current.map((flight) =>
            flight.flight_code === selectedFlight.flight_code ? updated : flight
          )
        );
      }
      setSelectedFlight(null);
      setEditorMode(null);
      setForm(null);
    } catch (err) {
      setModalError(err instanceof Error ? err.message : "No se pudo guardar el vuelo.");
    } finally {
      setSaving(false);
    }
  };

  return (
    <div className="app-shell">
      <Navbar />

      <main className="dashboard-workspace flights-workspace">
        <section className="dashboard-heading">
          <div>
            <h1>Vuelos</h1>
            <p>Listado completo de planes de vuelo leidos desde la base de datos.</p>
          </div>
          <button
            className="primary"
            onClick={openCreator}
            disabled={loading || !airportOptions.length || !assignedAirportTime}
            title={!assignedAirportTime ? "Asigna un aeropuerto por defecto para crear vuelos." : undefined}
          >
            Nuevo
          </button>
        </section>

        {error && <div className="error">{error}</div>}

        <section className="dashboard-grid">
          <div className="panel section metric-panel">
            <span>Total</span>
            <strong>{flights.length}</strong>
            <small>vuelos registrados</small>
          </div>
          <div className="panel section metric-panel">
            <span>Programados</span>
            <strong>{scheduledCount}</strong>
            <small>planes disponibles</small>
          </div>
          <div className="panel section metric-panel">
            <span>Cancelados</span>
            <strong>{canceledCount}</strong>
            <small>fuera de operacion</small>
          </div>
        </section>

        <section className="panel section flights-panel">
          {assignedAirportTime && (
            <div className="success" style={{ marginBottom: "1rem" }}>
              {`Mostrando vuelos con origen o destino en ${assignedAirportTime.code} - ${
                assignedAirportTime.city || "aeropuerto asignado"
              }.`}
            </div>
          )}

          <div className="flights-toolbar">
            <div className="field">
              <label>Buscar</label>
              <input
                type="search"
                placeholder="Codigo, UUID, fecha o estado"
                value={search}
                onChange={(event) => setSearch(event.target.value)}
              />
            </div>
            <div className="field">
              <label>Estado</label>
              <select value={status} onChange={(event) => setStatus(event.target.value)}>
                <option value="ALL">Todos</option>
                {statuses.map((item) => (
                  <option key={item} value={item}>
                    {item}
                  </option>
                ))}
              </select>
            </div>
          </div>

          <div className="flights-table-wrap">
            <table className="data-table flights-data-table">
              <thead>
                <tr>
                  <th>CODIGO</th>
                  <th>AEROPUERTO_ORIGEN</th>
                  <th>AEROPUERTO_DESTINO</th>
                  <th>SALIDA_LOCAL</th>
                  <th>LLEGADA_LOCAL</th>
                  <th>SALIDA_UTC</th>
                  <th>LLEGADA_UTC</th>
                  <th>CAPACIDAD</th>
                  <th>STATUS</th>
                </tr>
              </thead>
              <tbody>
                {visible.map((flight) => (
                  <tr
                    key={flight.flight_code}
                    className="clickable-row"
                    onClick={() => openEditor(flight)}
                    tabIndex={0}
                    onKeyDown={(event) => {
                      if (event.key === "Enter" || event.key === " ") {
                        event.preventDefault();
                        openEditor(flight);
                      }
                    }}
                  >
                    <td>
                      <strong>{flight.flight_code}</strong>
                    </td>
                    <td>{flight.origin}</td>
                    <td>{flight.destination}</td>
                    <td>{formatDateTime(flight.departure_time_local)}</td>
                    <td>{formatDateTime(flight.arrival_time_local)}</td>
                    <td>{formatDateTime(flight.departure_time_utc)}</td>
                    <td>{formatDateTime(flight.arrival_time_utc)}</td>
                    <td>{flight.capacity.toLocaleString("es-PE")}</td>
                    <td>
                      <span className={`status-pill ${isCanceled(flight) ? "inactive" : "active"}`}>
                        {statusLabel(flight)}
                      </span>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>

            {!visible.length && (
              <div className="empty-state">
                {loading ? "Cargando vuelos desde la BD..." : "No se encontraron vuelos."}
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

      {editorMode && form && (
        <div className="modal-backdrop" role="presentation" onMouseDown={closeEditor}>
          <div
            className="airport-modal"
            role="dialog"
            aria-modal="true"
            aria-labelledby="flight-editor-title"
            onMouseDown={(event) => event.stopPropagation()}
          >
            <div className="modal-header">
              <div>
                <h2 id="flight-editor-title">
                  {editorMode === "create" ? "Nuevo vuelo" : `Editar ${selectedFlight?.flight_code}`}
                </h2>
                <span>
                  {editorMode === "create"
                    ? `Se guardara como SCHEDULED desde ${assignedAirportTime?.code || "aeropuerto asignado"}`
                    : `${selectedFlight?.origin} -> ${selectedFlight?.destination}`}
                </span>
              </div>
              <button className="icon-button" type="button" onClick={closeEditor} disabled={saving}>
                x
              </button>
            </div>

            <form className="airport-form" onSubmit={saveFlight}>
              <div className="field">
                <label>AEROPUERTO_DESTINO</label>
                <select
                  value={form.destinationAirportCode}
                  onChange={(event) => updateForm("destinationAirportCode", event.target.value)}
                  required
                >
                  {!airportOptions.some((airport) => airport.code === form.destinationAirportCode) && (
                    <option value={form.destinationAirportCode}>{form.destinationAirportCode}</option>
                  )}
                  {airportOptions.map((airport) => (
                    <option key={airport.code} value={airport.code}>
                      {airport.code}
                    </option>
                  ))}
                </select>
              </div>
              <div className="field">
                <label>SALIDA_LOCAL</label>
                <input
                  type="datetime-local"
                  step="1"
                  value={form.departureTimeLocal}
                  onChange={(event) => updateForm("departureTimeLocal", event.target.value)}
                  required
                />
              </div>
              <div className="field">
                <label>LLEGADA_LOCAL</label>
                <input
                  type="datetime-local"
                  step="1"
                  value={form.arrivalTimeLocal}
                  onChange={(event) => updateForm("arrivalTimeLocal", event.target.value)}
                  required
                />
              </div>
              <div className="field">
                <label>CAPACIDAD</label>
                <input
                  type="number"
                  min="1"
                  step="1"
                  value={form.capacity}
                  onChange={(event) => updateForm("capacity", Number(event.target.value))}
                  required
                />
              </div>
              {editorMode === "edit" && (
                <div className="field">
                  <label>STATUS</label>
                  <select
                    value={form.status}
                    onChange={(event) =>
                      updateForm("status", event.target.value as "SCHEDULED" | "CANCELED")
                    }
                  >
                    <option value="SCHEDULED">SCHEDULED</option>
                    <option value="CANCELED">CANCELED</option>
                  </select>
                </div>
              )}

              {modalError && <div className="error modal-error">{modalError}</div>}

              <div className="modal-actions">
                <button className="ghost" type="button" onClick={closeEditor} disabled={saving}>
                  Cancelar
                </button>
                <button className="primary" type="submit" disabled={saving}>
                  {saving ? "Guardando..." : editorMode === "create" ? "Crear vuelo" : "Guardar cambios"}
                </button>
              </div>
            </form>
          </div>
        </div>
      )}
    </div>
  );
}
