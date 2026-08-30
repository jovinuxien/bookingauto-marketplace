/** What GET /api/vehicles/{plate} answers: the car, and nothing about its owner. */
export interface VehicleView {
  /** Normalised: ABC123 */
  registrationNumber: string;
  /** As on the car: ABC 123 */
  display: string;
  make: string;
  model: string | null;
  modelYear: number | null;
  tyreFront: string | null;
  tyreRear: string | null;
  /** "215/55R16", or "front / rear" when they differ */
  tyres: string | null;
  /** "VOLVO V70 (2016)" */
  description: string;
}

/**
 * The lookup as a state machine, so a page can say the right thing.
 *
 *   idle         nothing typed, or typed and not yet asked
 *   looking      asked, waiting
 *   found        the car
 *   unknown      the register does not know the plate (404) -- booking may go on
 *   unavailable  the registry could not be asked (503) -- booking may go on, at list price
 *   invalid      not a plate (400)
 *   throttled    too many lookups from this address (429)
 */
export type VehicleLookup =
  | { state: 'idle' }
  | { state: 'looking'; plate: string }
  | { state: 'found'; plate: string; vehicle: VehicleView }
  | { state: 'unknown'; plate: string }
  | { state: 'unavailable'; plate: string }
  | { state: 'invalid' }
  | { state: 'throttled' };
