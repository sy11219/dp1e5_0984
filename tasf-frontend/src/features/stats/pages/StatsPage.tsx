import { Navbar } from "../../global/Navbar";

export const StatsPage = () => {
  return (
    <div className="app-shell">
      <Navbar/>

      <div className="workspace" style={{ padding: "24px" }}>
        <h1>Estadísticas</h1>
        <p>
          Esta es la sección de Estadísticas. Aquí se mostrarán los contenidos
          relacionados a las estadísticas del aeropuerto.
        </p>
      </div>
    </div>
  );
};