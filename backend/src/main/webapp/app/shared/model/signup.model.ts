/** The registration form, as the server expects it. */
export interface Registration {
  salonName: string;
  email: string;
  password: string;
  addressLine: string;
  postalCode: string;
  city: string;
}

/** Field name to message, rendered next to the input that caused it. */
export type FieldProblems = Partial<Record<keyof Registration, string>>;

/**
 * What the salon needs to do next, returned once when the link is clicked.
 *
 * calPassword is null when the Cal account already existed — which is what a
 * retried verification looks like — and is shown exactly once when it is not.
 */
export interface SignupReady {
  salonName: string;
  slug: string;
  calUsername: string;
  calPassword: string | null;
  kycUrl: string;
}
