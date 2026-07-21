import { createPortal } from "react-dom"
import { Button } from "@/components/ui/button"
import type { SimulationData } from "../../types"
import { computeAirportLoads } from "../../utils/calculations"
import { formatDateOnly, formatTimeOnly } from "../../utils/formatters"

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

  const summaryMetrics = data.lastPlanningMetrics ?? data.metrics
  const totalShipments = summaryMetrics.shipments
  const totalBags = summaryMetrics.totalBags
  const plannedShipments = summaryMetrics.plannedShipments
  const plannedBags = summaryMetrics.plannedBags
  const deliveredShipments = summaryMetrics.deliveredShipments ?? summaryMetrics.onTimeShipments
  const deliveredBags = summaryMetrics.deliveredBags ?? plannedBags
  const onTimeShipments = summaryMetrics.onTimeShipments
  const onTimeBags = summaryMetrics.onTimeBags ?? plannedBags
  const plannedShipmentPct = formatPercent(plannedShipments, totalShipments)
  const plannedBagPct = formatPercent(plannedBags, totalBags)
  const firstWarehouseShipments = summaryMetrics.firstWarehouseShipments ?? 0
  const firstWarehouseBags = summaryMetrics.firstWarehouseBags ?? 0
  const inTransitShipments = summaryMetrics.inTransitShipments ??
    Math.max(0, plannedShipments - deliveredShipments - firstWarehouseShipments)
  const inTransitBags = summaryMetrics.inTransitBags ??
    Math.max(0, plannedBags - deliveredBags - firstWarehouseBags)
  const deliveredPlannedPct = formatPercent(deliveredShipments, plannedShipments)
  const deliveredPlannedBagPct = formatPercent(deliveredBags, plannedBags)
  const inTransitPct = formatPercent(inTransitShipments, plannedShipments)
  const inTransitBagPct = formatPercent(inTransitBags, plannedBags)
  const firstWarehousePct = formatPercent(firstWarehouseShipments, plannedShipments)
  const firstWarehouseBagPct = formatPercent(firstWarehouseBags, plannedBags)
  const onTimeShipmentPct = formatPercent(onTimeShipments, totalShipments)
  const onTimeBagPct = formatPercent(onTimeBags, totalBags)
  const bagsPerFlight = summaryMetrics.usedFlights
    ? (plannedBags / summaryMetrics.usedFlights).toFixed(1)
    : "0.0"
  const algorithmRuntimeMs = lastPlanningRuntimeMs(data)
  const hasPlanningWindow = isFinitePlanningMinute(data.lastBatchStart) && isFinitePlanningMinute(data.lastBatchEnd)
  const planningStart = hasPlanningWindow
    ? simulationMinuteToDate(data, data.lastBatchStart)
    : data.simulationStartDateTime
  const planningEnd = hasPlanningWindow
    ? simulationMinuteToDate(data, data.lastBatchEnd)
    : data.simulationEndDateTime
  const stoppedMinute = data.status === "COMPLETED"
    ? data.maxTick ?? data.tick
    : data.visualEndTick ?? data.tick ?? data.maxTick
  const simulatedElapsedMinutes = Math.max(
    0,
    (stoppedMinute ?? data.startOffsetMinutes ?? 0) - (data.startOffsetMinutes ?? 0)
  )
  const simulatedElapsedMs = simulatedElapsedMinutes * 60_000
  const simulationStoppedAt =
    data.simulationStoppedDateTime ?? simulationMinuteToDate(data, stoppedMinute)
  const mapMinute = data.status === "COMPLETED"
    ? data.maxTick ?? data.tick ?? 0
    : data.visualEndTick ?? data.tick ?? data.maxTick ?? 0
  const airportLoads = computeAirportLoads(data, mapMinute)
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
            <span>{hasPlanningWindow ? "Inicio de planificación" : "Inicio de simulación"}</span>
            <strong>
              {formatDateOnly(planningStart)} {formatTimeOnly(planningStart)}
            </strong>
          </div>
          <div>
            <span>{hasPlanningWindow ? "Fin de planificación" : "Fin de simulación"}</span>
            <strong>
              {formatDateOnly(planningEnd)} {formatTimeOnly(planningEnd)}
            </strong>
          </div>
          <div>
            <span>Detención de simulación</span>
            <strong>
              {formatDateOnly(simulationStoppedAt)} {formatTimeOnly(simulationStoppedAt)}
            </strong>
          </div>
        </div>

        <div className="summary-modal-grid">
          <SummaryMetric
            label="Planificación"
            rows={[
              metricRow("Envíos", plannedShipments, totalShipments, plannedShipmentPct),
              metricRow("Maletas", plannedBags, totalBags, plannedBagPct),
            ]}
            value={`${summaryMetrics.plannedShipments}/${summaryMetrics.shipments}`}
            sub={`${plannedShipmentPct}% envíos | ${plannedBagPct}% maletas`}
          />
          <SummaryMetric
            label="Entregado"
            rows={[
              metricRow("Envíos", deliveredShipments, plannedShipments, deliveredPlannedPct),
              metricRow("Maletas", deliveredBags, plannedBags, deliveredPlannedBagPct),
            ]}
            value={deliveredShipments}
            sub={`${deliveredPlannedPct}% planificados | ${deliveredBags} maletas (${deliveredPlannedBagPct}%)`}
          />
          <SummaryMetric
            label="En tránsito"
            rows={[
              metricRow("Envíos", inTransitShipments, plannedShipments, inTransitPct),
              metricRow("Maletas", inTransitBags, plannedBags, inTransitBagPct),
            ]}
            value={inTransitShipments}
            sub={`${inTransitPct}% planificados | ${inTransitBags} maletas (${inTransitBagPct}%)`}
          />
          <SummaryMetric
            label="En primer almacén"
            rows={[
              metricRow("Envíos", firstWarehouseShipments, plannedShipments, firstWarehousePct),
              metricRow("Maletas", firstWarehouseBags, plannedBags, firstWarehouseBagPct),
            ]}
            value={firstWarehouseShipments}
            sub={`${firstWarehousePct}% planificados | ${firstWarehouseBags} maletas (${firstWarehouseBagPct}%)`}
          />
          <SummaryMetric
            label="A tiempo"
            rows={[
              metricRow("Envíos", onTimeShipments, totalShipments, onTimeShipmentPct),
              metricRow("Maletas", onTimeBags, totalBags, onTimeBagPct),
            ]}
            value={`${summaryMetrics.onTimeShipments}/${summaryMetrics.shipments}`}
            sub={`${onTimeShipmentPct}% envíos | ${onTimeBagPct}% maletas`}
          />
          <SummaryMetric
            label="Vuelos usados"
            value={summaryMetrics.usedFlights}
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
            label="Tiempo de planificación"
            value={algorithmRuntimeMs === null ? "--" : formatDurationHms(algorithmRuntimeMs, { ceilPositiveSeconds: true })}
            sub="última planificación"
          />
          <SummaryMetric
            label="Duración de la simulación"
            value={formatDurationHms(realTimeMs)}
          />
          <SummaryMetric
            label="Tiempo simulado"
            value={formatDurationHms(simulatedElapsedMs)}
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

function isFinitePlanningMinute(value: number | undefined): value is number {
  return typeof value === "number" && Number.isFinite(value) && value >= 0
}

function simulationMinuteToDate(data: SimulationData, minute: number | undefined): Date | undefined {
  if (!isFinitePlanningMinute(minute) || !data.simulationStartDateTime) return undefined

  const simulationStart = Date.parse(data.simulationStartDateTime)
  if (!Number.isFinite(simulationStart)) return undefined

  return new Date(simulationStart + (minute - (data.startOffsetMinutes ?? 0)) * 60_000)
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

type SummaryMetricRow = {
  label: string
  value: string
  percent: string
}

function metricRow(label: string, part: number, total: number, percent: string): SummaryMetricRow {
  return {
    label,
    value: `${part}/${total}`,
    percent: `${percent}%`,
  }
}

function SummaryMetric({
  label,
  value,
  sub,
  rows,
}: {
  label: string
  value?: string | number
  sub?: string | number
  rows?: SummaryMetricRow[]
}) {
  return (
    <div className="summary-metric">
      <span>{label}</span>
      {rows?.length ? (
        <div className="summary-metric-rows">
          {rows.map((row) => (
            <div className="summary-metric-row" key={row.label}>
              <small>{row.label}</small>
              <strong>{row.value}</strong>
              <em>{row.percent}</em>
            </div>
          ))}
        </div>
      ) : (
        <>
          <strong>{value}</strong>
          {sub !== undefined && sub !== "" && <small>{sub}</small>}
        </>
      )}
    </div>
  )
}
