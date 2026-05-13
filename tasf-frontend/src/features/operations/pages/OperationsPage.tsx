import { Navbar } from "../../global/Navbar";

export const OperationsPage = () => {
  return (
    <div className="app-shell">
      <Navbar/>

      <div className="workspace" style={{ padding: "24px" }}>
        <h1>Operaciones</h1>
        <p>
          Esta es la sección de Operaciones. Aquí se mostrarán los contenidos
          relacionados a la operación del aeropuerto.
        </p>
      </div>
    </div>
  );
};