import { useCallback, useEffect, useRef, useState } from "react";

/**
 * Hook que anima simMinute desde un valor inicial hasta un valor objetivo,
 * durante una duración real fija, usando requestAnimationFrame.
 *
 * Diseñado para sincronizarse con lotes del backend:
 *   - Cuando llega un lote nuevo (targetMinute sube), la animación
 *     interpola desde el tick anterior hasta el nuevo durante BATCH_DURATION_MS.
 *   - Al terminar la animación, onBatchComplete() notifica al componente padre
 *     para que pida el siguiente lote.
 */

const BATCH_MINUTES = 180;          // minutos simulados por lote (3 horas)
const BATCH_DURATION_MS = 120_000;  // duración real de cada lote (2 minutos)

export function useSimulationPlayer(maxMinute: number) {
  const [simMinute, setSimMinute] = useState(0);
  const [playing, setPlaying]     = useState(false);

  // Objetivo actual de la animación (tick que devolvió el último /advance)
  const targetMinuteRef  = useRef(0);
  // Minuto desde el que arranca la animación del lote actual
  const startMinuteRef   = useRef(0);
  // Timestamp real en que empezó a animar el lote actual
  const batchStartTimeRef = useRef<number | null>(null);

  // Callback que el componente padre registra para recibir el aviso
  // "terminé de animar este lote, pide el siguiente"
  const onBatchCompleteRef = useRef<(() => void) | null>(null);

  const frame = useRef<number | null>(null);

  // Cancela el frame activo
  const cancelFrame = useCallback(() => {
    if (frame.current !== null) {
      cancelAnimationFrame(frame.current);
      frame.current = null;
    }
  }, []);

  /**
   * Inicia la animación de un nuevo lote.
   * @param fromMinute  minuto simulado de inicio (tick anterior)
   * @param toMinute    minuto simulado de fin (tick nuevo del backend)
   */
  const animateBatch = useCallback((fromMinute: number, toMinute: number) => {
    cancelFrame();
    startMinuteRef.current   = Math.min(Math.max(0, fromMinute), maxMinute);
    targetMinuteRef.current  = Math.min(Math.max(0, toMinute), maxMinute);
    batchStartTimeRef.current = null; // se fija en el primer frame

    const tick = (now: number) => {
      if (batchStartTimeRef.current === null) {
        batchStartTimeRef.current = now;
      }

      const elapsed = now - batchStartTimeRef.current;
      const progress = Math.min(elapsed / BATCH_DURATION_MS, 1);
      const interpolated = startMinuteRef.current +
        progress * (targetMinuteRef.current - startMinuteRef.current);

      setSimMinute(interpolated);

      if (progress < 1) {
        frame.current = requestAnimationFrame(tick);
      } else {
        // Animación del lote terminada → avisar al padre
        frame.current = null;
        onBatchCompleteRef.current?.();
      }
    };

    frame.current = requestAnimationFrame(tick);
  }, [cancelFrame, maxMinute]);

  // Limpieza al desmontar
  useEffect(() => () => cancelFrame(), [cancelFrame]);

  /**
   * Reinicia toda la animación al estado inicial.
   */
  const reset = useCallback(() => {
    cancelFrame();
    setSimMinute(0);
    setPlaying(false);
    targetMinuteRef.current   = 0;
    startMinuteRef.current    = 0;
    batchStartTimeRef.current = null;
  }, [cancelFrame]);

  return {
    simMinute,
    setSimMinute,
    playing,
    setPlaying,
    animateBatch,
    reset,
    onBatchCompleteRef,
    BATCH_MINUTES,
    speed: BATCH_MINUTES,
    setSpeed: () => {},
  };
}
