import { useEffect, useRef } from "react"
import { useLocation } from "react-router-dom"
import {
  advanceBatchSimulationRequest,
  getCurrentBatchSimulationRequest,
} from "../../../api/simulationApi"
import type { SimulationData } from "../types"

const PAUSED_KEY = "tasf.simulation5d.paused"
const STOPPED_KEY = "tasf.simulation5d.stoppedSessionId"
const POLL_MS = 5_000
const DEFAULT_BATCH_MINUTES = 180
const DEFAULT_BATCH_INTERVAL_MS = 120_000

function isPausedLocally() {
  try {
    return window.localStorage.getItem(PAUSED_KEY) === "true"
  } catch {
    return false
  }
}

function isStoppedLocally(data: SimulationData) {
  if (!data.simulationId) return false

  try {
    return window.localStorage.getItem(STOPPED_KEY) === data.simulationId
  } catch {
    return false
  }
}

function shouldAdvanceBatch(data: SimulationData) {
  if (!data.simulationId || data.status === "COMPLETED") return false

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
  const location = useLocation()
  const advancingRef = useRef(false)

  useEffect(() => {
    let cancelled = false

    const coordinate = async () => {
      if (cancelled || location.pathname === "/" || advancingRef.current || isPausedLocally()) {
        return
      }

      try {
        const current = await getCurrentBatchSimulationRequest()
        if (current && isStoppedLocally(current)) return
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
  }, [location.pathname])

  return null
}
