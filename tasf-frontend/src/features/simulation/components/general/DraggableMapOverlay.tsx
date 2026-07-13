import { useRef, useState } from "react";
import type { CSSProperties, ReactNode, PointerEvent } from "react";

type DraggableMapOverlayProps = {
  children: ReactNode;
  initialX?: number;
  initialY?: number;
  anchor?: "top-left" | "bottom-right";
  className?: string;
};

export function DraggableMapOverlay({
  children,
  initialX = 18,
  initialY = 18,
  anchor = "top-left",
  className = "",
}: DraggableMapOverlayProps) {
  const dragStart = useRef<{ pointerId: number; x: number; y: number; offsetX: number; offsetY: number } | null>(null);
  const [position, setPosition] = useState({ offsetX: initialX, offsetY: initialY });
  const [dragging, setDragging] = useState(false);

  const startDrag = (event: PointerEvent<HTMLDivElement>) => {
    const target = event.target as HTMLElement | null;
    if (target?.closest("button, input, select, textarea, a")) return;

    event.currentTarget.setPointerCapture(event.pointerId);
    dragStart.current = {
      pointerId: event.pointerId,
      x: event.clientX,
      y: event.clientY,
      offsetX: position.offsetX,
      offsetY: position.offsetY,
    };
    setDragging(true);
  };

  const moveDrag = (event: PointerEvent<HTMLDivElement>) => {
    const start = dragStart.current;
    if (!start || start.pointerId !== event.pointerId) return;

    const deltaX = event.clientX - start.x;
    const deltaY = event.clientY - start.y;

    setPosition(
      anchor === "bottom-right"
        ? {
            offsetX: Math.max(0, start.offsetX - deltaX),
            offsetY: Math.max(0, start.offsetY - deltaY),
          }
        : {
            offsetX: Math.max(0, start.offsetX + deltaX),
            offsetY: Math.max(0, start.offsetY + deltaY),
          }
    );
  };

  const endDrag = (event: PointerEvent<HTMLDivElement>) => {
    if (dragStart.current?.pointerId === event.pointerId) {
      dragStart.current = null;
      setDragging(false);
    }
  };

  const style: CSSProperties =
    anchor === "bottom-right"
      ? {
          left: "auto",
          top: "auto",
          right: 0,
          bottom: 0,
          transform: `translate(${-position.offsetX}px, ${-position.offsetY}px)`,
        }
      : {
          left: 0,
          top: 0,
          transform: `translate(${position.offsetX}px, ${position.offsetY}px)`,
        };

  return (
    <div
      className={["map-draggable-overlay", dragging ? "is-dragging" : "", className].filter(Boolean).join(" ")}
      style={style}
      onPointerDown={startDrag}
      onPointerMove={moveDrag}
      onPointerUp={endDrag}
      onPointerCancel={endDrag}
    >
      {children}
    </div>
  );
}
