export interface Flight {
  flightId: string;

  originCode: string;

  destCode: string;

  departureMinute: number;

  arrivalMinute: number;

  dayOffset: number;

  maxCapacity: number;

  assignedLoad: number;
}