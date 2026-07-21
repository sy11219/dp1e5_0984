# TasfB2BSoft

## Descripción general
**TasfB2BSoft** ha sido desarrollado con el propósito de resolver la problemática logística de la empresa TasfB2B: tiempos de entrega ajustados, capacidad limitada y falta de visibilidad integral.  
La plataforma integra los escenarios de **simulación de 5 días**, **operaciones día a día** y **simulación hasta el colapso**, ofreciendo a gerentes técnicos y comerciales una visión clara y accionable de toda la red logística.

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

### 3. Simulación hasta el colapso
- **Simulación continua**: se ejecuta una misma simulación desde la fecha ingresada hasta el colapso de las operaciones, es decir, la fecha en la que ya no se pueda cumplir con las restricciones asignadas.

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
├── ALNS_Tasfb2b/                                      # Código backend del proyecto 
│
├── diagrams/                                          # Diagramas de interacción entre navegadores y de consumo de datos, y esquema de la BD
│
├── tasf-frontend/                                     # Código frontend del proyecto
│
├── tools/
│   └── generate_db_schema_image.py                    # Script para la generación del esquema de la BD
|
├── DEPLOYMENT.md                                      # Guía de despliegue
|
└── README.md                                          # Archivo README del proyecto

```

## Instrucciones de uso

### Simulación de 5 días
Para acceder el escenario, ingresar a la página correspondiente (Tiempo real). Adentro, se visualizarán todos los vuelos en proceso, los cuales inicialmente estarán vacíos. En la pantalla de Vuelos, se pueden agregar envíos ya sea manualmente o a través de archivos .txt con el formato **000000001-20260102-00-47-SUAA-002-0032535** (ID_Envío-Fecha-HH-MM-ID_Destino-Num_Maletas-ID_Cliente) por cada línea de texto, los cuales se utilizarán en el escenario. El sistema está diseñado para detectar la zona horaria del dispositivo y seleccionar automáticamente el aeropuerto respectivo.

### Operaciones día a día
Para acceder el escenario, ingresar a la página correspondiente (Simulación). Adentro, completar los datos de fecha y hora de inicio de la simulación, y presionar "Ejecutar simulación". El algoritmo planificador calculará las asignaciones del primer batch de datos y a continuación se procederán a cargas los envíos en su panel respectivo. Este escenario utiliza los datos históricos cargados en el sistema. El usuario creador de la simulación puede pausarla, reiniciarla o cancelarla, con los botones respectivos del panel izquierdo. El sistema está diseñado para que esta misma simulación se transmita a otros usuarios que accedan al sistema, hasta que esta termine o sea cancelada.

### Simulación hasta el colapso
Para acceder el escenario, ingresar a la página correspondiente (Colapso). Adentro, completar los datos de fecha y hora de inicio de la simulación, y presionar "Ejecutar colapso". Se ejecutará una simulación similar a la de 5 días, con la diferencia de que esta se ejecutará continuamente hasta que se alcancen las condiciones de colapso. Una vez ocurra esto, aparecerá un reporte en pantalla con las estadísticas de la última planificación, y la razón del colapso.

### Agregar datos
Los usuarios pueden agregar datos y modificar datos de Aeropuertos, Planes de Vuelo y Envíos en las páginas correspondientes. Solo se pueden agregar envíos con origen en el aeropuerto asignado según la zona horaria.
