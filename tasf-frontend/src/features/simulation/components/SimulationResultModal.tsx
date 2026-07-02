import { createPortal } from "react-dom"
import { Button } from "@/components/ui/button"
import type { SimulationData } from "../types"
import { computeAirportLoads } from "../utils/calculations"
import { formatDateOnly, formatTimeOnly } from "../utils/formatters"

interface SimulationModalProps {
  open: boolean
  onOpenChange: (open: boolean) => void
  data: SimulationData | null
  realTimeMs: number
  title?: string
  description?: string
  showFooter?: boolean
  footerLabel?: string
  closeOnOutside?: boolean
  showHeaderClose?: boolean
}

export function SimulationResultModal({
  open,
  onOpenChange,
  data,
  realTimeMs,
  title = "Simulación finalizada",
  description = 'Más información en "Estadísticas"',
  showFooter = true,
  footerLabel = "Aceptar",
  closeOnOutside = true,
  showHeaderClose = showFooter,
}: SimulationModalProps) {
  if (!open || !data) return null

  const deliveredShipments = data.metrics.deliveredShipments ?? data.metrics.onTimeShipments
  const deliveredBags = data.metrics.deliveredBags ?? data.metrics.plannedBags
  const onTimeBags = data.metrics.onTimeBags ?? data.metrics.plannedBags
  const plannedShipmentPct = formatPercent(data.metrics.plannedShipments, data.metrics.shipments)
  const plannedBagPct = formatPercent(data.metrics.plannedBags, data.metrics.totalBags)
  const deliveredShipmentPct = formatPercent(deliveredShipments, data.metrics.shipments)
  const deliveredBagPct = formatPercent(deliveredBags, data.metrics.totalBags)
  const onTimeShipmentPct = formatPercent(data.metrics.onTimeShipments, data.metrics.shipments)
  const onTimeBagPct = formatPercent(onTimeBags, data.metrics.totalBags)
  const bagsPerFlight = data.metrics.usedFlights
    ? (data.metrics.totalBags / data.metrics.usedFlights).toFixed(1)
    : "0.0"
  const simulatedDays = data.days ?? Math.round(((data.maxTick ?? data.tick ?? 0) / 1440))
  const algorithmRuntimeMs = lastPlanningRuntimeMs(data)
  const finalMinute = data.maxTick ?? data.tick ?? 0
  const airportLoads = computeAirportLoads(data, finalMinute)
  const airportBags = data.airports.reduce(
    (sum, airport) => sum + Math.max(0, airportLoads[airport.code] || 0),
    0
  )
  const airportCapacity = data.airports.reduce(
    (sum, airport) => sum + Math.max(0, airport.maxCapacity || 0),
    0
  )
  const assignedBags = data.flights.reduce(
    (sum, flight) => sum + Math.max(0, flight.assignedLoad || 0),
    0
  )
  const fleetCapacity = data.flights.reduce(
    (sum, flight) => sum + Math.max(0, flight.maxCapacity || 0),
    0
  )

  return createPortal(
    <div
      className={`summary-modal-layer ${closeOnOutside ? "" : "summary-modal-layer-pass-through"}`}
      role="presentation"
      onMouseDown={closeOnOutside ? () => onOpenChange(false) : undefined}
    >
      <section
        className="summary-modal"
        role="dialog"
        aria-modal="false"
        aria-labelledby="simulation-summary-title"
        onMouseDown={(event) => event.stopPropagation()}
      >
        <header className="summary-modal-header">
          <div>
            <h2 id="simulation-summary-title">{title}</h2>
            {description && <p>{description}</p>}
          </div>
          {showHeaderClose && (
            <button
              className="icon-button"
              type="button"
              aria-label="Cerrar resumen"
              onClick={() => onOpenChange(false)}
            >
              x
            </button>
          )}
        </header>

        <div className="summary-modal-times">
          <div>
            <span>Inicio de simulación</span>
            <strong>
              {formatDateOnly(data.simulationStartDateTime)} {formatTimeOnly(data.simulationStartDateTime)}
            </strong>
          </div>
          <div>
            <span>Fin de simulación</span>
            <strong>
              {formatDateOnly(data.simulationEndDateTime)} {formatTimeOnly(data.simulationEndDateTime)}
            </strong>
          </div>
        </div>

        <div className="summary-modal-grid">
          <SummaryMetric
            label="Planificación"
            value={`${data.metrics.plannedShipments}/${data.metrics.shipments}`}
            sub={`${plannedShipmentPct}% envíos | ${plannedBagPct}% maletas`}
          />
          <SummaryMetric
            label="Entregado"
            value={`${deliveredShipments}/${data.metrics.shipments}`}
            sub={`${deliveredShipmentPct}% envíos | ${deliveredBagPct}% maletas`}
          />
          <SummaryMetric
            label="A tiempo"
            value={`${data.metrics.onTimeShipments}/${data.metrics.shipments}`}
            sub={`${onTimeShipmentPct}% envíos | ${onTimeBagPct}% maletas`}
          />
          <SummaryMetric
            label="Maletas"
            value={data.metrics.plannedBags}
            sub={`de ${data.metrics.totalBags}`}
          />
          <SummaryMetric
            label="Vuelos usados"
            value={data.metrics.usedFlights}
            sub={`${bagsPerFlight} maletas/vuelo`}
          />
          <SummaryMetric
            label="Ocupación flota"
            value={`${formatPercent(assignedBags, fleetCapacity)}%`}
            sub={`${assignedBags}/${fleetCapacity} maletas`}
          />
          <SummaryMetric
            label="Ocupación aeropuertos"
            value={`${formatPercent(airportBags, airportCapacity)}%`}
            sub={`${airportBags}/${airportCapacity} maletas`}
          />
          <SummaryMetric
            label="Tiempo de ejecución del algoritmo"
            value={algorithmRuntimeMs === null ? "--" : formatDurationHms(algorithmRuntimeMs, { ceilPositiveSeconds: true })}
            sub="última planificación"
          />
          <SummaryMetric
            label="Duración de la simulación"
            value={formatDurationHms(realTimeMs)}
            sub={`${simulatedDays} días simulados`}
          />
        </div>

        {showFooter && (
          <footer className="summary-modal-footer">
            <Button onClick={() => onOpenChange(false)}>{footerLabel}</Button>
          </footer>
        )}
      </section>
    </div>,
    document.body
  )
}

function formatPercent(part: number, total: number): string {
  const value = total ? (part / total) * 100 : 0
  return value.toLocaleString("es-PE", {
    minimumFractionDigits: 2,
    maximumFractionDigits: 2,
  })
}

function lastPlanningRuntimeMs(data: SimulationData): number | null {
  const candidates = [
    data.lastBatchRuntimeMs,
    data.planningExecutionMs,
  ]

  for (const value of candidates) {
    if (typeof value === "number" && value > 0) return value
  }

  return null
}

function formatDurationHms(
  ms: number,
  options?: { ceilPositiveSeconds?: boolean }
): string {
  const totalSec = Math.max(
    0,
    options?.ceilPositiveSeconds && ms > 0 ? Math.ceil(ms / 1000) : Math.floor(ms / 1000)
  )
  const h = Math.floor(totalSec / 3600)
  const m = Math.floor((totalSec % 3600) / 60)
  const s = totalSec % 60

  return `${h.toString().padStart(2, "0")}:${m.toString().padStart(2, "0")}:${s
    .toString()
    .padStart(2, "0")}`
}

function SummaryMetric({
  label,
  value,
  sub,
}: {
  label: string
  value: string | number
  sub: string | number
}) {
  return (
    <div className="summary-metric">
      <span>{label}</span>
      <strong>{value}</strong>
      <small>{sub}</small>
    </div>
  )
}
