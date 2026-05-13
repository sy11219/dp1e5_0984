import { NavLink } from "react-router-dom";
import "./Navbar.css";

export function Navbar() {
  const pages = [
    { name: "Operaciones", path: "/operations" },
    { name: "Simulación", path: "/" },
    { name: "Gestión de Maletas", path: "/baggage" },
    { name: "Estadísticas", path: "/stats" },
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