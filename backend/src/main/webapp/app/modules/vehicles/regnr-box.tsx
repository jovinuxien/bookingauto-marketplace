import React, { useEffect, useState } from 'react';
import axios from 'app/config/axiosinstance';
import type { ApiError } from 'app/config/axios-interceptor';
import type { VehicleLookup, VehicleView } from 'app/shared/model/vehicle.model';

/**
 * The registration number, asked first (ADR 0016).
 *
 * One box, used on the search page, the provider page and checkout, so the
 * plate is typed once and the same words are said about it everywhere. It
 * asks the server on Enter or the button, never per keystroke: every lookup
 * that misses the cache is a paid call, and the endpoint counts them per
 * address.
 *
 * Every outcome except "invalid" lets the customer continue. A plate the
 * register does not know is still a plate the workshop can read, and a
 * registry that is down is not the customer's problem -- the page says list
 * prices apply and moves on. The one thing this box never does is block a
 * booking on a third party.
 */
export const normalisePlate = (typed: string) => typed.replace(/[\s-]/g, '').toUpperCase();

export const lookupVehicle = async (plate: string): Promise<VehicleLookup> => {
  const normalised = normalisePlate(plate);

  if (!/^[A-Z0-9]{2,8}$/.test(normalised)) {
    return { state: 'invalid' };
  }

  try {
    const response = await axios.get<VehicleView>(`/vehicles/${normalised}`);
    return { state: 'found', plate: normalised, vehicle: response.data };
  } catch (error) {
    const failure = error as ApiError;
    switch (failure.status) {
      case 404: return { state: 'unknown', plate: normalised };
      case 400: return { state: 'invalid' };
      case 429: return { state: 'throttled' };
      default:  return { state: 'unavailable', plate: normalised };
    }
  }
};

interface Props {
  /** The plate already known, from the URL or a previous page. */
  initial?: string;
  /** Called with the normalised plate whenever a lookup settles on one. */
  onPlate: (plate: string, lookup: VehicleLookup) => void;
  /** Smaller variant for the filter row. */
  compact?: boolean;
}

export const RegnrBox = ({ initial, onPlate, compact }: Props) => {
  const [typed, setTyped] = useState(initial ?? '');
  const [lookup, setLookup] = useState<VehicleLookup>({ state: 'idle' });

  // A plate that arrived in the URL is looked up once, so the page can
  // greet the car without the customer pressing anything.
  useEffect(() => {
    if (initial && lookup.state === 'idle') {
      void run(initial);
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [initial]);

  const run = async (plate: string) => {
    const normalised = normalisePlate(plate);
    if (!normalised) {
      return;
    }
    setLookup({ state: 'looking', plate: normalised });
    const result = await lookupVehicle(normalised);
    setLookup(result);
    if (result.state !== 'invalid' && result.state !== 'throttled') {
      onPlate(normalised, result);
    }
  };

  const submit = (event: React.FormEvent) => {
    event.preventDefault();
    void run(typed);
  };

  return (
    <form onSubmit={submit} className={compact ? '' : 'mb-3'}>
      <label className={compact ? 'visually-hidden' : 'form-label'} htmlFor="regnr">
        Registreringsnummer
      </label>
      <div className={`input-group ${compact ? 'input-group-sm w-auto' : ''}`}>
        <input
          id="regnr"
          className="form-control font-monospace text-uppercase"
          style={compact ? { maxWidth: '8.5rem' } : undefined}
          value={typed}
          placeholder="ABC 123"
          autoComplete="off"
          maxLength={10}
          aria-describedby="regnr-status"
          onChange={event => setTyped(event.target.value)}
        />
        <button className="btn btn-outline-primary" type="submit"
          disabled={lookup.state === 'looking' || !typed.trim()}>
          {lookup.state === 'looking' ? 'Hämtar…' : 'Hämta bil'}
        </button>
      </div>
      <div id="regnr-status" className="form-text" aria-live="polite">
        <Status lookup={lookup} />
      </div>
    </form>
  );
};

const Status = ({ lookup }: { lookup: VehicleLookup }) => {
  switch (lookup.state) {
    case 'idle':
      return <>Så att verkstaden vet vilken bil som kommer.</>;
    case 'looking':
      return <>Hämtar uppgifter om {lookup.plate}…</>;
    case 'found':
      return (
        <span className="text-body">
          <strong>{lookup.vehicle.description}</strong>
          {lookup.vehicle.tyres && <> · däck {lookup.vehicle.tyres}</>}
        </span>
      );
    case 'unknown':
      return <>Vi hittar ingen bil med numret {lookup.plate}. Du kan boka ändå.</>;
    case 'unavailable':
      return <>Kunde inte hämta biluppgifter just nu — listpris visas. Du kan boka ändå.</>;
    case 'invalid':
      return <span className="text-danger">Det ser inte ut som ett registreringsnummer.</span>;
    case 'throttled':
      return <span className="text-danger">För många sökningar just nu. Prova om en stund.</span>;
  }
};
