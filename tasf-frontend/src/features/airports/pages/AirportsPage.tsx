import { useEffect, useMemo, useState, type FormEvent } from "react";
import {
  createAirportRequest,
  getAirportsRequest,
  updateAirportRequest,
  type AirportUpdatePayload,
} from "../../../api/simulationApi";
import { Navbar } from "../../../shared/components/Navbar/Navbar";
import type { Airport } from "../../simulation/types";
import {
  assignManualAirportTime,
  useAssignedAirportTime,
} from "../../simulation/utils/assignedAirportTime";

const PAGE_SIZE = 12;

type StatusFilter = "ALL" | "ACTIVE" | "INACTIVE";
type AirportForm = AirportUpdatePayload;
type EditorMode = "create" | "edit";

function formatNumber(value: number | undefined, digits = 0) {
  if (value === undefined || Number.isNaN(value)) return "--";
  return value.toLocaleString("es-PE", {
    minimumFractionDigits: digits,
    maximumFractionDigits: digits,
  });
}

function statusLabel(airport: Airport) {
  if (airport.operationalStatus) return airport.operationalStatus;
  return airport.active === false ? "INACTIVE" : "ACTIVE";
}

function isActive(airport: Airport) {
  if (typeof airport.active === "boolean") return airport.active;
  return statusLabel(airport).toUpperCase() === "ACTIVE";
}

function airportToForm(airport: Airport): AirportForm {
  return {
    city: airport.city || "",
    country: airport.country || "",
    continent: airport.continent || "",
    operationalStatus: isActive(airport) ? "ACTIVE" : "INACTIVE",
    latitude: airport.latitude || 0,
    longitude: airport.longitude || 0,
    gmtOffset: airport.gmtOffset ?? 0,
    maxCapacity: airport.maxCapacity || 1,
  };
}

function emptyAirportForm(): AirportForm {
  return {
    city: "",
    country: "",
    continent: "",
    operationalStatus: "ACTIVE",
    latitude: 0,
    longitude: 0,
    gmtOffset: 0,
    maxCapacity: 1,
  };
}

export function AirportsPage() {
  const assignedAirportTime = useAssignedAirportTime();
  const [airports, setAirports] = useState<Airport[]>([]);
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState("");
  const [modalError, setModalError] = useState("");
  const [search, setSearch] = useState("");
  const [continent, setContinent] = useState("ALL");
  const [status, setStatus] = useState<StatusFilter>("ALL");
  const [page, setPage] = useState(1);
  const [selectedAirport, setSelectedAirport] = useState<Airport | null>(null);
  const [editorMode, setEditorMode] = useState<EditorMode | null>(null);
  const [newCode, setNewCode] = useState("");
  const [form, setForm] = useState<AirportForm | null>(null);
  const [assignOpen, setAssignOpen] = useState(false);

  const loadAirports = async () => {
    setLoading(true);
    setError("");

    try {
      const payload = await getAirportsRequest();
      setAirports(payload);
      setPage(1);
    } catch (err) {
      setError(err instanceof Error ? err.message : "No se pudieron cargar los aeropuertos.");
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    void loadAirports();
  }, []);

  useEffect(() => {
    setPage(1);
  }, [search, continent, status]);

  const continents = useMemo(
    () =>
      Array.from(new Set(airports.map((airport) => airport.continent).filter(Boolean))).sort(),
    [airports]
  );

  const filtered = useMemo(() => {
    const query = search.trim().toLowerCase();

    return airports
      .filter((airport) => {
        if (!query) return true;
        return [
          airport.code,
          airport.city,
          airport.country,
          airport.continent,
          airport.operationalStatus,
        ]
          .filter(Boolean)
          .some((value) => String(value).toLowerCase().includes(query));
      })
      .filter((airport) => continent === "ALL" || airport.continent === continent)
      .filter((airport) => {
        if (status === "ALL") return true;
        return status === "ACTIVE" ? isActive(airport) : !isActive(airport);
      })
      .sort((a, b) => a.code.localeCompare(b.code));
  }, [airports, continent, search, status]);

  const pageCount = Math.max(1, Math.ceil(filtered.length / PAGE_SIZE));
  const safePage = Math.min(page, pageCount);
  const visible = filtered.slice((safePage - 1) * PAGE_SIZE, safePage * PAGE_SIZE);
  const activeCount = airports.filter(isActive).length;
  const inactiveCount = airports.length - activeCount;

  const airportOptions = useMemo(
    () => [...airports].sort((a, b) => a.code.localeCompare(b.code)),
    [airports]
  );

  const assignAirport = (code: string) => {
    const airport = airportOptions.find((item) => item.code === code);
    if (!airport) return;
    assignManualAirportTime({
      code: airport.code,
      city: airport.city || "",
      gmtOffset: airport.gmtOffset ?? 0,
    });
  };

  const openEditor = (airport: Airport) => {
    setSelectedAirport(airport);
    setEditorMode("edit");
    setForm(airportToForm(airport));
    setModalError("");
  };

  const openCreator = () => {
    setSelectedAirport(null);
    setEditorMode("create");
    setNewCode("");
    setForm(emptyAirportForm());
    setModalError("");
  };

  const closeEditor = () => {
    if (saving) return;
    setSelectedAirport(null);
    setEditorMode(null);
    setNewCode("");
    setForm(null);
    setModalError("");
  };

  const updateForm = <K extends keyof AirportForm>(key: K, value: AirportForm[K]) => {
    setForm((current) => (current ? { ...current, [key]: value } : current));
  };

  const saveAirport = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (!editorMode || !form) return;

    setSaving(true);
    setModalError("");

    try {
      if (editorMode === "create") {
        const created = await createAirportRequest({
          code: newCode.trim().toUpperCase(),
          city: form.city,
          country: form.country,
          continent: form.continent,
          latitude: form.latitude,
          longitude: form.longitude,
          gmtOffset: form.gmtOffset,
          maxCapacity: form.maxCapacity,
        });
        setAirports((current) => [...current, created]);
      } else if (selectedAirport) {
        const updated = await updateAirportRequest(selectedAirport.code, form);
        setAirports((current) =>
          current.map((airport) => (airport.code === updated.code ? updated : airport))
        );
      }
      setSelectedAirport(null);
      setEditorMode(null);
      setNewCode("");
      setForm(null);
    } catch (err) {
      setModalError(err instanceof Error ? err.message : "No se pudo guardar el aeropuerto.");
    } finally {
      setSaving(false);
    }
  };

  return (
    <div className="app-shell">
      <Navbar />

      <main className="dashboard-workspace airports-workspace">
        <section className="dashboard-heading">
          <div>
            <h1>Aeropuertos</h1>
            <p>Catalogo completo leído desde la base de datos al iniciar la aplicación.</p>
          </div>
          <div className="toolbar-actions">
            <button
              className="ghost"
              onClick={() => setAssignOpen((open) => !open)}
              disabled={loading || !airportOptions.length}
            >
              Asignar
            </button>
            <button className="primary" onClick={openCreator} disabled={loading}>
              Nuevo
            </button>
          </div>
        </section>

        {error && <div className="error">{error}</div>}

        {assignOpen && (
          <section className="panel section airports-panel">
            <div className="airports-toolbar">
              <div className="field">
                <label>Aeropuerto por defecto</label>
                <select
                  value={assignedAirportTime?.code || ""}
                  onChange={(event) => assignAirport(event.target.value)}
                >
                  <option value="" disabled>
                    Selecciona un aeropuerto
                  </option>
                  {airportOptions.map((airport) => (
                    <option key={airport.code} value={airport.code}>
                      {`${airport.code} - ${airport.city}`}
                    </option>
                  ))}
                </select>
              </div>
              <div className="metric-panel">
                <span>Actual</span>
                <strong>{assignedAirportTime?.code || "Sin asignar"}</strong>
                <small>
                  {assignedAirportTime
                    ? `${assignedAirportTime.city} - UTC${
                        (assignedAirportTime.gmtOffset ?? 0) >= 0 ? "+" : ""
                      }${assignedAirportTime.gmtOffset ?? 0}`
                    : "se usara la deteccion automatica si encuentra coincidencia"}
                </small>
              </div>
            </div>
          </section>
        )}

        <section className="dashboard-grid">
          <div className="panel section metric-panel">
            <span>Total</span>
            <strong>{airports.length}</strong>
            <small>aeropuertos registrados</small>
          </div>
          <div className="panel section metric-panel">
            <span>Activos</span>
            <strong>{activeCount}</strong>
            <small>disponibles para operar</small>
          </div>
          <div className="panel section metric-panel">
            <span>Inactivos</span>
            <strong>{inactiveCount}</strong>
            <small>fuera de operación</small>
          </div>
        </section>

        <section className="panel section airports-panel">
          <div className="airports-toolbar">
            <div className="field">
              <label>Buscar</label>
              <input
                type="search"
                placeholder="Código, ciudad, país o continente"
                value={search}
                onChange={(event) => setSearch(event.target.value)}
              />
            </div>
            <div className="field">
              <label>Continente</label>
              <select value={continent} onChange={(event) => setContinent(event.target.value)}>
                <option value="ALL">Todos</option>
                {continents.map((item) => (
                  <option key={item} value={item}>
                    {item}
                  </option>
                ))}
              </select>
            </div>
            <div className="field">
              <label>Estado</label>
              <select
                value={status}
                onChange={(event) => setStatus(event.target.value as StatusFilter)}
              >
                <option value="ALL">Todos</option>
                <option value="ACTIVE">Activos</option>
                <option value="INACTIVE">Inactivos</option>
              </select>
            </div>
          </div>

          <div className="airports-table-wrap">
            <table className="data-table airports-data-table">
              <thead>
                <tr>
                  <th>Codigo</th>
                  <th>Ciudad</th>
                  <th>Pais</th>
                  <th>Continente</th>
                  <th>Estado</th>
                  <th>Latitud</th>
                  <th>Longitud</th>
                  <th>GMT</th>
                  <th>Capacidad</th>
                </tr>
              </thead>
              <tbody>
                {visible.map((airport) => (
                  <tr
                    key={airport.code}
                    className="clickable-row"
                    onClick={() => openEditor(airport)}
                    tabIndex={0}
                    onKeyDown={(event) => {
                      if (event.key === "Enter" || event.key === " ") {
                        event.preventDefault();
                        openEditor(airport);
                      }
                    }}
                  >
                    <td>
                      <strong>{airport.code}</strong>
                    </td>
                    <td>{airport.city}</td>
                    <td>{airport.country}</td>
                    <td>{airport.continent}</td>
                    <td>
                      <span className={`status-pill ${isActive(airport) ? "active" : "inactive"}`}>
                        {statusLabel(airport)}
                      </span>
                    </td>
                    <td>{formatNumber(airport.latitude, 4)}</td>
                    <td>{formatNumber(airport.longitude, 4)}</td>
                    <td>
                      {airport.gmtOffset === undefined
                        ? "--"
                        : `UTC${airport.gmtOffset >= 0 ? "+" : ""}${airport.gmtOffset}`}
                    </td>
                    <td>{formatNumber(airport.maxCapacity)}</td>
                  </tr>
                ))}
              </tbody>
            </table>

            {!visible.length && (
              <div className="empty-state">
                {loading ? "Cargando aeropuertos desde la BD..." : "No se encontraron aeropuertos."}
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
            aria-labelledby="airport-editor-title"
            onMouseDown={(event) => event.stopPropagation()}
          >
            <div className="modal-header">
              <div>
                <h2 id="airport-editor-title">
                  {editorMode === "create" ? "Nuevo aeropuerto" : `Editar ${selectedAirport?.code}`}
                </h2>
                <span>
                  {editorMode === "create"
                    ? "Se guardara como ACTIVE"
                    : selectedAirport?.city}
                </span>
              </div>
              <button className="icon-button" type="button" onClick={closeEditor} disabled={saving}>
                x
              </button>
            </div>

            <form className="airport-form" onSubmit={saveAirport}>
              {editorMode === "create" && (
                <div className="field">
                  <label>Codigo</label>
                  <input
                    value={newCode}
                    onChange={(event) => setNewCode(event.target.value.toUpperCase())}
                    required
                    minLength={4}
                    maxLength={4}
                    pattern="[A-Z]{4}"
                    placeholder="SPJC"
                  />
                </div>
              )}
              <div className="field">
                <label>Ciudad</label>
                <input
                  value={form.city}
                  onChange={(event) => updateForm("city", event.target.value)}
                  required
                />
              </div>
              <div className="field">
                <label>Pais</label>
                <input
                  value={form.country}
                  onChange={(event) => updateForm("country", event.target.value)}
                  required
                />
              </div>
              <div className="field">
                <label>Continente</label>
                <input
                  value={form.continent}
                  onChange={(event) => updateForm("continent", event.target.value)}
                  required
                />
              </div>
              {editorMode === "edit" && (
                <div className="field">
                  <label>Estado</label>
                  <select
                    value={form.operationalStatus}
                    onChange={(event) =>
                      updateForm("operationalStatus", event.target.value as "ACTIVE" | "INACTIVE")
                    }
                  >
                    <option value="ACTIVE">ACTIVE</option>
                    <option value="INACTIVE">INACTIVE</option>
                  </select>
                </div>
              )}
              <div className="field">
                <label>Latitud</label>
                <input
                  type="number"
                  step="0.000001"
                  min="-90"
                  max="90"
                  value={form.latitude}
                  onChange={(event) => updateForm("latitude", Number(event.target.value))}
                  required
                />
              </div>
              <div className="field">
                <label>Longitud</label>
                <input
                  type="number"
                  step="0.000001"
                  min="-180"
                  max="180"
                  value={form.longitude}
                  onChange={(event) => updateForm("longitude", Number(event.target.value))}
                  required
                />
              </div>
              <div className="field">
                <label>GMT</label>
                <input
                  type="number"
                  step="1"
                  min="-12"
                  max="14"
                  value={form.gmtOffset}
                  onChange={(event) => updateForm("gmtOffset", Number(event.target.value))}
                  required
                />
              </div>
              <div className="field">
                <label>Capacidad</label>
                <input
                  type="number"
                  step="1"
                  min="1"
                  value={form.maxCapacity}
                  onChange={(event) => updateForm("maxCapacity", Number(event.target.value))}
                  required
                />
              </div>

              {modalError && <div className="error modal-error">{modalError}</div>}

              <div className="modal-actions">
                <button className="ghost" type="button" onClick={closeEditor} disabled={saving}>
                  Cancelar
                </button>
                <button className="primary" type="submit" disabled={saving}>
                  {saving ? "Guardando..." : editorMode === "create" ? "Crear aeropuerto" : "Guardar cambios"}
                </button>
              </div>
            </form>
          </div>
        </div>
      )}
    </div>
  );
}
