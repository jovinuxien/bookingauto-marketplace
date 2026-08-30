export interface Session {
  email: string;
  displayName: string | null;
  providerId: number | null;
  role: string;
}

export interface ConsoleSummary {
  name: string;
  status: string;
  onboardingState: string;
  payoutsEnabled: boolean;
  activeServices: number;
  /** The salon's share of confirmed sales, in minor units. */
  earnedMinor: number;
  /** What the platform kept. Shown, not netted away. */
  commissionMinor: number;
  upcomingCount: number;
}

export interface ConsoleBooking {
  id: number;
  startsAt: string;
  endsAt: string;
  customerName: string;
  customerEmail: string;
  priceMinor: number;
  commissionMinor: number;
  currency: string;
  status: string;
  serviceName: string;
  calBookingUid: string;
  /** Null for a salon's booking. */
  registrationNumber: string | null;
  /** "Volvo V70 (2016)" once looked up; null until then, or forever if no registry. */
  vehicle: string | null;
  /** "Spolarvätska, Däckhotell" — what to fetch from the shelf; null for none. */
  addons: string | null;
}

/** One service on the pricing page, with its rules (ADR 0016). */
export interface ServicePricing {
  service: { id: number; name: string; priceMinor: number; currency: string; active: boolean; asksVehicle: boolean };
  rules: PriceRule[];
  addons: { id: number; serviceId: number; name: string; priceMinor: number }[];
}

export interface PriceRule {
  id: number;
  serviceId: number;
  make: string | null;
  modelPrefix: string | null;
  yearFrom: number | null;
  yearTo: number | null;
  rimFrom: number | null;
  rimTo: number | null;
  priceMinor: number;
  label: string;
}

export interface NewPriceRule {
  make?: string;
  modelPrefix?: string;
  yearFrom?: number;
  yearTo?: number;
  rimFrom?: number;
  rimTo?: number;
  priceMinor: number;
  label?: string;
}

/** "Vad kostar det för ABC 123?" */
export interface ConsoleQuote {
  plate: string;
  vehicle: string | null;
  tyres: string | null;
  registryUnavailable: boolean;
  quote: { priceMinor: number; label: string | null; forVehicle: boolean };
}

/** An attempt no machine could finish. */
export interface AttentionItem {
  id: number;
  slotStart: string;
  customerEmail: string;
  failure: string | null;
  updatedAt: string;
  calBookingUid: string | null;
  paymentRef: string | null;
}
