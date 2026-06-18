export function CapacityLegend() {
  return (
    <section className="panel section">
      <h3>Colores por capacidad</h3>
      <div className="legend">
        <div className="legend-row"><span className="dot green"></span>Menor a 70%</div>
        <div className="legend-row"><span className="dot yellow"></span>Desde 70% hasta menor a 90%</div>
        <div className="legend-row"><span className="dot red"></span>90% o mas</div>
      </div>
    </section>
  );
}
