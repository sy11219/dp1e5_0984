import { useEffect, useRef, useState } from "react";

export function useSimulationPlayer(maxMinute: number) {
  const [simMinute, setSimMinute] = useState(0);

  const [playing, setPlaying] = useState(false);

  //const [speed, setSpeed] = useState(360);
  const FIXED_SPEED = 40;
  const frame = useRef<number | null>(null);

  useEffect(() => {
    if (!playing) return;

    let last = performance.now();

    const tick = (time: number) => {
      const elapsedSeconds = (time - last) / 1000;

      last = time;

      setSimMinute((minute) => {
        const next = Math.min(
          maxMinute,
          minute + elapsedSeconds * FIXED_SPEED
        );

        if (next >= maxMinute) {
          setPlaying(false);
        }

        return next;
      });

      frame.current = requestAnimationFrame(tick);
    };

    frame.current = requestAnimationFrame(tick);

    return () => {
      if (frame.current) {
        cancelAnimationFrame(frame.current);
      }
    };
  }, [playing, maxMinute]); //añadir speed para la barra

  return {
    simMinute,
    setSimMinute,
    playing,
    setPlaying,
    speed: FIXED_SPEED,
    setSpeed: () => {}, // noop
  };
}