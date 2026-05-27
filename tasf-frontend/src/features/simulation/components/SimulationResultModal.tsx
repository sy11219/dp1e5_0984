import {
  Dialog,
  DialogContent,
  DialogHeader,
  DialogTitle,
  DialogDescription,
  DialogFooter,
} from "@/components/ui/dialog"
import { Button } from "@/components/ui/button"
import type { SimulationData } from "../types"

interface SimulationModalProps {
  open: boolean
  onOpenChange: (open: boolean) => void
  data: SimulationData | null
}

export function SimulationResultModal({ open, onOpenChange, data }: SimulationModalProps) {
  if (!data) return null
  const bagCompletionRate = ((data.metrics.plannedBags / data.metrics.totalBags) * 100).toFixed(0)
  const onTimeRate = ((data.metrics.onTimeShipments / data.metrics.shipments) * 100).toFixed(0)
  const bagsPerFlight = (data.metrics.totalBags / data.metrics.usedFlights).toFixed(1)
  const executionTimeSec = (data.runtimeMs / 1000).toFixed(2)

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="text-center max-w-md z-[500]">
        <DialogHeader>
          <DialogTitle className="text-2xl font-semibold">Simulación finalizada</DialogTitle>
        </DialogHeader>

        <div className="space-y-4 py-6 text-left md:text-center">
          <p className="text-lg">
            Maletas entregadas: {data.metrics.plannedBags}/{data.metrics.totalBags} ({bagCompletionRate}%)
          </p>
          <p className="text-lg">
            Entregas a tiempo: {data.metrics.onTimeShipments}/{data.metrics.shipments} ({onTimeRate}%)
          </p>
          <p className="text-lg">
            Vuelos (total): {data.metrics.usedFlights} ({bagsPerFlight} maletas/vuelo)
          </p>
          <p className="text-lg">
            Tiempo de ejecución del algoritmo: {executionTimeSec} s
          </p>
          <p className="text-lg">
            {data.days} días simulados en XX:XX
          </p>
        </div>

        <DialogDescription className="text-center text-muted-foreground">
          Más información en "Estadísticas"
        </DialogDescription>

        <DialogFooter className="justify-center sm:justify-center mt-4">
          <Button onClick={() => onOpenChange(false)}>Aceptar</Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  )
}