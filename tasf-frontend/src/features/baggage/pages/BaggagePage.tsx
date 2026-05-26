import { Navbar } from "../../../shared/components/Navbar/Navbar";

export const BaggagePage = () => {
  return (
    <div className="app-shell">
      <Navbar/>

      <div className="workspace" style={{ padding: "24px" }}>
        <h1>Gestión de Maletas</h1>
        <p>
          Esta es la sección de Gestión de Maletas. Aquí se mostrarán los contenidos
          relacionados a la gestión de maletas en el aeropuerto.
        </p>
      </div>
    </div>
  );
};