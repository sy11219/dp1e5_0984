import { useEffect, useRef } from "react"
import {
  advanceBatchSimulationRequest,
  getCurrentBatchSimulationRequest,
  ownsBatchSimulation,
} from "../../../api/simulationApi"
import type { SimulationData } from "../types"

const POLL_MS = 5_000
const DEFAULT_BATCH_MINUTES = 180
const DEFAULT_BATCH_INTERVAL_MS = 120_000

function shouldAdvanceBatch(data: SimulationData) {
  if (!data.simulationId || data.status !== "RUNNING") return false

  const tick = data.tick ?? data.startOffsetMinutes ?? 0
  if (tick >= (data.maxTick ?? Number.POSITIVE_INFINITY)) return false

  const visualStart = data.visualStartTick ?? data.lastBatchStart ?? data.startOffsetMinutes ?? 0
  const visualEnd = data.visualEndTick ?? data.tick ?? visualStart
  if (visualEnd <= visualStart) return true

  const visualStartedAt = data.visualStartedAt ? Date.parse(data.visualStartedAt) : Number.NaN
  if (!Number.isFinite(visualStartedAt)) return false

  const intervalMs = data.planningIntervalMs ?? data.batchIntervalMs ?? DEFAULT_BATCH_INTERVAL_MS
  return Date.now() - visualStartedAt >= intervalMs
}

export function BatchSimulationCoordinator() {
  const advancingRef = useRef(false)

  useEffect(() => {
    let cancelled = false

    const coordinate = async () => {
      if (cancelled || advancingRef.current) {
        return
      }

      try {
        const current = await getCurrentBatchSimulationRequest()
        if (current && !ownsBatchSimulation(current)) return
        if (cancelled || !current || !shouldAdvanceBatch(current) || !current.simulationId) return

        advancingRef.current = true
        await advanceBatchSimulationRequest(
          current.simulationId,
          current.planningWindowMinutes ?? current.batchMinutes ?? DEFAULT_BATCH_MINUTES,
          current.tick ?? current.startOffsetMinutes ?? 0
        )
      } catch {
        // The visible page will surface backend connectivity errors when the user returns.
      } finally {
        advancingRef.current = false
      }
    }

    void coordinate()
    const timer = window.setInterval(coordinate, POLL_MS)

    return () => {
      cancelled = true
      window.clearInterval(timer)
    }
  }, [])

  return null
}
