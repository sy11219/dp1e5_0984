export interface Airport {
  code: string;

  city: string;

  country: string;

  continent: string;

  maxCapacity: number;

  currentLoad: number;

  latitude: number;

  longitude: number;

  gmtOffset: number;
}