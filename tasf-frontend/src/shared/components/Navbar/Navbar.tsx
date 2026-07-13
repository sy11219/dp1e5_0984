import { NavLink } from "react-router-dom";
import "./Navbar.css";

export function Navbar() {
  const pages = [
    { name: "Tiempo real", path: "/operations" },
    { name: "Simulación", path: "/" },
    { name: "Colapso", path: "/collapse" },
    { name: "Gestión de Maletas", path: "/baggage" },
    { name: "Estadísticas", path: "/stats" },
    { name: "Aeropuertos", path: "/airports" },
    { name: "Vuelos", path: "/flights" },
    { name: "Envíos", path: "/shipments" },
  ];

  return (
    <nav className="navbar">
      {pages.map((p) => (
        <NavLink
          key={p.name}
          to={p.path}
          className={({ isActive }) =>
            isActive ? "nav-link active" : "nav-link"
          }
        >
          {p.name}
        </NavLink>
      ))}
    </nav>
  );
}
