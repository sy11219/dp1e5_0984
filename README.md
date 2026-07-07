# TasfB2BSoft

## Descripción general
**TasfB2BSoft** ha sido desarrollado con el propósito de resolver la problemática logística de la empresa TasfB2B: tiempos de entrega ajustados, capacidad limitada y falta de visibilidad integral.  
La plataforma integra los escenarios de **simulación de 5 días** y **operaciones día a día**, ofreciendo a gerentes técnicos y comerciales una visión clara y accionable de toda la red logística.

## Escenarios principales

### General
El sistema permite:
- Visualizar los vuelos y almacenes de la empresa en un mapa del mundo.
- Planificar y asignar maletas a planes de vuelo, asegurando cumplimiento de límites de tiempo de entrega.
- Visualizar de manera inmediata el estado de ocupación de vuelos y almacenes, mediante semaforización.
- Agregar y editar los datos de planes de vuelo y aeropuertos.
- Agregar datos de envíos para la operación diaria de la empresa.
- Detección del aeropuerto de origen y hora de salida de un envío basado en la zona horaria del dispositivo desde donde se agregue.
- Registrar cancelaciones durante los escenarios, y replanificar los vuelos asignados acordemente.
- Visualizar indicadores globales de ocupación y cumplimiento operativo.
- Visualizar e interactuar con listas de aeropuertos, vuelos y envíos, permitiendo interactividad con el mapa y aplicación de filtros y ordenaciones.
- Visualizar estadísticas de cumplimiento y asignación de maletas.

### 1. Simulación de 5 días
- **Horizonte de 5 días**: proyecta y visualiza gráficamente el avance de la operación.
- **Anticipación de cuellos de botella**: identifica saturaciones de capacidad y riesgos de incumplimiento antes de que ocurran. 
- **Priorización algorítmica**: ordena automáticamente envíos según urgencia y disponibilidad de vuelos.

### 2. Operaciones día a día
- **Asignación dinámica de vuelos y envíos**: refleja la actividad operativa minuto a minuto, con un consumo periódico de datos.  
- **Cancelaciones en tiempo real**: permite la cancelación de vuelos y replanificación de envíos durante la operación en tiempo real.
- **Visualización de vuelos en tiempo real**: los vuelos en el mapa se mueven en tiempo real. 

## Stack tecnológico

Frontend:
- React: Biblioteca principal para la creación de la interfaz.
- React Router: Para la navegación y enrutamiento.
- Leaflet: Librería utilizada para la visualización del mapa interactivo.
- Tailwind CSS: Estilizado de componentes.

Backend:
- Java: Lenguaje de programación principal para la programación del algoritmo planificador y la API REST. Algoritmo utilizado: Adaptive Large Neighborhood Search (ALNS).

---

## Estructura del proyecto

```
dp1e5_0984/
│
├── ALNS_Tasfb2b/        # Código backend del proyecto 
│
├── outputs/
│   └── db_schema_tasfb2b.png # Esquema de la base de datos
│
├── tasf-frontend/       # Código frontend del proyecto
│
├── tools/
│   └── generate_db_schema_image.py # Script para la generación del esquema de la BD
|
├── DEPLOYMENT.md        # Guía de despliegue
|
└── README.md            # Archivo README del proyecto

```
