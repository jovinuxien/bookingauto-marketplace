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
