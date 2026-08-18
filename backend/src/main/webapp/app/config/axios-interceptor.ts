import axiosInstance from 'app/config/axiosinstance';

/**
 * Turns transport failures into something the UI can say out loud.
 *
 * The interceptor exists to stop every component inventing its own reading of
 * an error. Two cases matter here and are not the same:
 *
 *   409  the slot went while the customer was looking at it. Expected, and the
 *        right response is to offer other times -- not to retry, and not to
 *        apologise for a fault, because there is none.
 *   402  the payment was declined. The slot has already been released by the
 *        backend, so the customer can simply try again.
 */
export interface ApiError {
  status: number;
  message: string;
  /** True when retrying the identical request cannot help. */
  terminal: boolean;
  /**
   * The response body, when the server sent one.
   *
   * Carried through because some failures are not a sentence to show the user
   * but a structure to render — a signup rejected field by field being the
   * first. The generic message above still applies to everything that has no
   * better answer, and a caller reading this has to know its shape itself.
   */
  data?: unknown;
}

export const setupAxiosInterceptors = (onUnauthenticated: () => void) => {
  axiosInstance.interceptors.response.use(
    response => response,
    error => {
      const status: number | undefined = error?.response?.status;

      if (status === 401 || status === 403) {
        onUnauthenticated();
      }

      const apiError: ApiError = {
        status: status ?? 0,
        message: messageFor(status, error),
        terminal: status === 409 || status === 404,
        data: error?.response?.data,
      };

      return Promise.reject(apiError);
    }
  );
};

const messageFor = (status: number | undefined, error: unknown): string => {
  switch (status) {
    case 409:
      return 'Tiden hann bli bokad. Välj en annan tid.';
    case 402:
      return 'Betalningen gick inte igenom. Tiden är släppt — försök gärna igen.';
    case 404:
      return 'Hittades inte.';
    case undefined:
    case 0:
      // No response at all: the request may or may not have been received, so
      // this must never read as "nothing happened".
      return 'Vi vet inte om din begäran gick fram. Ladda om sidan innan du försöker igen.';
    default:
      return 'Något gick fel. Försök igen om en stund.';
  }
};

export default setupAxiosInterceptors;
