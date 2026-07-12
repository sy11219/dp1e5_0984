import { useRef, useState } from "react";
import type { ReactNode, PointerEvent } from "react";

type DraggableMapOverlayProps = {
  children: ReactNode;
  initialX?: number;
  initialY?: number;
  className?: string;
};

export function DraggableMapOverlay({
  children,
  initialX = 18,
  initialY = 18,
  className = "",
}: DraggableMapOverlayProps) {
  const dragStart = useRef<{ pointerId: number; x: number; y: number; left: number; top: number } | null>(null);
  const [position, setPosition] = useState({ left: initialX, top: initialY });
  const [dragging, setDragging] = useState(false);

  const startDrag = (event: PointerEvent<HTMLDivElement>) => {
    const target = event.target as HTMLElement | null;
    if (target?.closest("button, input, select, textarea, a")) return;

    event.currentTarget.setPointerCapture(event.pointerId);
    dragStart.current = {
      pointerId: event.pointerId,
      x: event.clientX,
      y: event.clientY,
      left: position.left,
      top: position.top,
    };
    setDragging(true);
  };

  const moveDrag = (event: PointerEvent<HTMLDivElement>) => {
    const start = dragStart.current;
    if (!start || start.pointerId !== event.pointerId) return;

    setPosition({
      left: Math.max(0, start.left + event.clientX - start.x),
      top: Math.max(0, start.top + event.clientY - start.y),
    });
  };

  const endDrag = (event: PointerEvent<HTMLDivElement>) => {
    if (dragStart.current?.pointerId === event.pointerId) {
      dragStart.current = null;
      setDragging(false);
    }
  };

  return (
    <div
      className={["map-draggable-overlay", dragging ? "is-dragging" : "", className].filter(Boolean).join(" ")}
      style={{ transform: `translate(${position.left}px, ${position.top}px)` }}
      onPointerDown={startDrag}
      onPointerMove={moveDrag}
      onPointerUp={endDrag}
      onPointerCancel={endDrag}
    >
      {children}
    </div>
  );
}
