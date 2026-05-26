export interface SimulationResponse {
  simulationId: string;
  scenario: string; // "ALNS"
  status: string;   // "COMPLETED"
  days: number;
  simulationStartDateTime: string;
  simulationEndDateTime: string;
  realStartedAt: string;
  realFinishedAt: string;
  runtimeMs: number;
  metrics: {
    shipments: number;
    plannedShipments: number;
    onTimeShipments: number;
    totalBags: number;
    plannedBags: number;
    usedFlights: number;
    fitnessInitial: number; 
    fitnessFinal: number;   
    iterations: number;
    globalImprovements: number;
    acceptedBySa: number;
  };

  airports: {
    code: string;
    city: string;
    country: string;
    continent: string;
    latitude: number;
    longitude: number;
    gmtOffset: number;
    maxCapacity: number;
    peakLoad: number;
    finalLoad: number;
    utilization: number;
    status: "green" | "yellow" | "red";
  }[];

  flights: {
    id: string;
    origin: string;
    destination: string;
    dayOffset: number;
    departureMinute: number;
    arrivalMinute: number;
    absoluteDepartureMinute: number;
    absoluteArrivalMinute: number;
    assignedLoad: number;
    maxCapacity: number;
    utilization: number;
    status: "green" | "yellow" | "red";
  }[];

  shipments: {
    id: string;
    clientId: string;
    origin: string;
    destination: string;
    requestMinute: number;
    suitcases: number;
    planned: boolean;
    onTime: boolean;
    estimatedArrival: number;
    delayMinutes: number;
    flightIds: string[];
  }[];

  airportEvents: {
    minute: number;
    airport: string;
    delta: number;
    type: "shipment_created" | "flight_departure" | "connection_arrival" | "connection_departure" | "final_arrival";
  }[];
}