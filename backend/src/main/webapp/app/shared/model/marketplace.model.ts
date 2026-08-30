/**
 * The API's shapes, as the SPA sees them.
 *
 * Kept deliberately close to what the backend returns rather than reshaped for
 * convenience. When Cal or the schema changes, the compiler should point at the
 * boundary rather than at whatever component happened to unpack a field.
 */

export interface SearchHit {
  providerId: number;
  slug: string;
  name: string;
  city: string;
  distanceMetres: number;
  serviceName: string;
  durationMinutes: number;
  priceMinor: number;
  currency: string;
  freeSlots: number;
  firstFreeAt: string | null;
  /**
   * How old the index row behind this result is.
   *
   * Surfaced rather than hidden because search is allowed to be stale by
   * design, and a result nobody can date is a result nobody can question.
   */
  indexAgeSeconds: number;
}

/**
 * How a sentence was read, returned alongside the results it produced.
 *
 * `applied` is what actually went into the query, not what the model proposed;
 * where the two differ, `ignored` says so in words. Both are rendered rather
 * than logged — a filter the customer cannot see is one they cannot correct,
 * and an empty page is otherwise indistinguishable from a city with no
 * availability.
 */
export interface AskedAnswer {
  /** One line in the customer's own language, or null when nothing read the text. */
  summary: string | null;
  /** Filters that were proposed and refused. Usually empty. */
  ignored: string[];
  applied: {
    categorySlug: string | null;
    day: string;
    partOfDay: PartOfDay;
    radiusMetres: number;
  };
  hits: SearchHit[];
}

export type PartOfDay = 'ANY' | 'MORNING' | 'AFTERNOON' | 'EVENING';

export interface Service {
  id: number;
  name: string;
  categorySlug: string;
  durationMinutes: number;
  priceMinor: number;
  currency: string;
  /** Checkout must ask for a registration number. */
  asksVehicle: boolean;
  /** What everyone pays when no rule matches; equals priceMinor unless a rule did. */
  listPriceMinor: number;
  /** The matching rule's label ("Volvo 2015–2019"), or null. */
  priceLabel: string | null;
  /** True when priceMinor is for the car in ?regnr=, not the list price. */
  pricedForVehicle: boolean;
}

export interface ProviderDetail {
  id: number;
  slug: string;
  name: string;
  city: string;
  addressLine: string | null;
  description: string | null;
  services: Service[];
}

/** Real times, read from Cal rather than from the index. */
export interface DaySlots {
  serviceId: number;
  day: string;
  starts: string[];
}

export type AttemptState =
  | 'STARTED'
  | 'RESERVED'
  | 'VERIFIED'
  | 'AWAITING_PAYMENT'
  | 'CHARGED'
  | 'CONFIRMED'
  | 'ABANDONED'
  | 'REFUSED'
  | 'VERIFY_FAILED'
  | 'CHARGE_FAILED'
  | 'CONFIRM_FAILED'
  | 'NEEDS_ATTENTION';

export interface BookingOutcome {
  attemptId: number;
  state: AttemptState;
  calBookingUid: string | null;
  failure: string | null;
  /** Present only while the customer still has to approve the payment. */
  clientSecret?: string | null;
}

export interface PartOfDayOption {
  value: 'ANY' | 'MORNING' | 'AFTERNOON' | 'EVENING';
  label: string;
}
