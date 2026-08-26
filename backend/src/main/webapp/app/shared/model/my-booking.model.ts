/**
 * A customer's own booking, as the server chooses to describe it.
 *
 * Deliberately without an id or a Cal reference. The page reaches this booking
 * with the token it already holds, so an identifier would let it do nothing new
 * — and both are values that end up in screenshots and support threads.
 */
export interface MyBooking {
  providerName: string;
  city: string;
  serviceName: string;
  startsAt: string;
  endsAt: string;
  priceMinor: number;
  currency: string;
  /** 'confirmed' | 'cancelled' | 'refunded' */
  status: string;
  customerName: string;
  /** Still in the future and not already cancelled. */
  cancellable: boolean;
  /** Cancelling right now would return the money. */
  refundable: boolean;
  /** The moment after which cancelling stops being free. */
  freeUntil: string;
  cutoffHours: number;
  /** The slot was released and the money was not. Someone is on it. */
  needsAttention: boolean;
}
