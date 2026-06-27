import {
  Dialog,
  DialogContent,
  DialogHeader,
  DialogTitle,
  DialogDescription,
  DialogFooter,
} from "@/components/ui/dialog"
import { Button } from "@/components/ui/button"
import { formatRealTime } from "../utils/timeUtils"
import type { SimulationData } from "../types"

interface SimulationModalProps {
  open: boolean
  onOpenChange: (open: boolean) => void
  data: SimulationData | null
  realTimeMs: number
  title?: string
  description?: string
  showFooter?: boolean
}

export function SimulationResultModal({
  open,
  onOpenChange,
  data,
  realTimeMs,
  title = "Simulación finalizada",
  description = 'Más información en "Estadísticas"',
  showFooter = true,
}: SimulationModalProps) {
  if (!data) return null

  const planningRate = data.metrics.totalBags
    ? ((data.metrics.plannedBags / data.metrics.totalBags) * 100).toFixed(0)
    : "0"
  const shipmentPlanningRate = data.metrics.shipments
    ? ((data.metrics.plannedShipments / data.metrics.shipments) * 100).toFixed(0)
    : "0"
  const deliveredShipments = data.metrics.deliveredShipments ?? data.metrics.onTimeShipments
  const deliveredBags = data.metrics.deliveredBags ?? data.metrics.plannedBags
  const deliveryRate = data.metrics.totalBags
    ? ((deliveredBags / data.metrics.totalBags) * 100).toFixed(0)
    : "0"
  const onTimeBags = data.metrics.onTimeBags ?? data.metrics.plannedBags
  const onTimeRate = data.metrics.shipments
    ? ((data.metrics.onTimeShipments / data.metrics.shipments) * 100).toFixed(0)
    : "0"
  const onTimeBagRate = data.metrics.totalBags
    ? ((onTimeBags / data.metrics.totalBags) * 100).toFixed(0)
    : "0"
  const bagsPerFlight = data.metrics.usedFlights
    ? (data.metrics.totalBags / data.metrics.usedFlights).toFixed(1)
    : "0.0"
  const executionTimeSec = (data.runtimeMs / 1000).toFixed(2)

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="text-center max-w-md">
        <DialogHeader>
          <DialogTitle className="text-2xl font-semibold">{title}</DialogTitle>
        </DialogHeader>

        <div className="space-y-4 py-6 text-left md:text-center">
          <p className="text-lg">
            Planificacion completa: {data.metrics.plannedShipments}/{data.metrics.shipments} envios ({shipmentPlanningRate}%) | {data.metrics.plannedBags}/{data.metrics.totalBags} maletas ({planningRate}%)
          </p>
          <p className="text-lg">
            Entregado dentro de 120h: {deliveredShipments}/{data.metrics.shipments} envios | {deliveredBags}/{data.metrics.totalBags} maletas ({deliveryRate}%)
          </p>
          <p className="text-lg">
            Cumplimiento SLA: {data.metrics.onTimeShipments}/{data.metrics.shipments} envios ({onTimeRate}%) | {onTimeBags}/{data.metrics.totalBags} maletas ({onTimeBagRate}%)
          </p>
          <p className="text-lg">
            Vuelos (total): {data.metrics.usedFlights} ({bagsPerFlight} maletas/vuelo)
          </p>
          <p className="text-lg">
            Tiempo de ejecución del algoritmo: {executionTimeSec} s
          </p>
          <p className="text-lg">
            {data.days} días simulados en {formatRealTime(realTimeMs)}
          </p>
        </div>

        {description && (
          <DialogDescription className="text-center text-muted-foreground">
            {description}
          </DialogDescription>
        )}

        {showFooter && (
          <DialogFooter className="justify-center sm:justify-center mt-4">
            <Button onClick={() => onOpenChange(false)}>Aceptar</Button>
          </DialogFooter>
        )}
      </DialogContent>
    </Dialog>
  )
}
