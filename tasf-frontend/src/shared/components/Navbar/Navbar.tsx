import { useState, useEffect } from "react";
import { NavLink, useLocation } from "react-router-dom";
import "./Navbar.css";

export function Navbar() {
  const [isExpanded, setIsExpanded] = useState(false);
  const location = useLocation();

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

  useEffect(() => {
    setIsExpanded(false);
  }, [location.pathname]);

  return (
    <nav className={`navbar ${isExpanded ? "expanded" : ""}`}>
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

      <button
        className="navbar-toggle"
        onClick={() => setIsExpanded(!isExpanded)}
        aria-label={isExpanded ? "Collapse menu" : "Expand menu"}
      >
        <span className="arrow">
          <strong>{isExpanded ? "-" : "+"}</strong>
        </span>
      </button>
    </nav>
  );
}