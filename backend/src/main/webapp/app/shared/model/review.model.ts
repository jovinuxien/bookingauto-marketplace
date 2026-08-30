/** One customer's verdict, as shown to strangers. */
export interface Review {
  bookingId: number;
  rating: number;
  comment: string | null;
  createdAt: string;
  /** "Anna A." — first name and an initial, never more. */
  author: string;
}

export interface ProviderReviews {
  summary: { average: number | null; count: number };
  recent: Review[];
}
