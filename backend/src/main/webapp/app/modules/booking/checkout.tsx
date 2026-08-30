import React, { useEffect, useState } from 'react';
import { Link, useParams, useSearchParams } from 'react-router-dom';

import { useAppDispatch, useAppSelector } from 'app/config/store';
import { book, checkoutReset } from 'app/shared/reducers/booking.reducer';
import { formatDay, formatPrice, formatTime } from 'app/shared/util/format';
import axios from 'app/config/axiosinstance';
import type { Addon, ProviderDetail } from 'app/shared/model/marketplace.model';

/**
 * Checkout.
 *
 * The screen where the marketplace stops being allowed to be wrong. Three
 * outcomes have to be told apart clearly, because they mean very different
 * things to the person reading them:
 *
 *   CONFIRMED         booked and paid
 *   AWAITING_PAYMENT  the slot is held, the payment is not finished. Swish is a
 *                     push payment, so this is the normal path, not an error
 *   REFUSED           the slot went while they were deciding. Not their fault
 *                     and not a failure of ours -- offer other times
 */
const Checkout = () => {
  const { serviceId } = useParams<{ serviceId: string }>();
  const [params] = useSearchParams();
  const dispatch = useAppDispatch();

  const start = params.get('start') ?? '';
  const slug = params.get('slug') ?? '';
  // Told by the link rather than fetched: the provider page already knew,
  // and the server checks again regardless -- a workshop is never sent a
  // booking with no car on it whatever the URL said.
  const asksVehicle = params.get('fordon') === '1';
  // The price the salon page showed. Sent back so a rule changed since is a
  // 409 with the new number rather than a silent charge (ADR 0016).
  const shownPrice = params.get('pris') ? Number(params.get('pris')) : undefined;

  const { outcome, submitting, error, priceNow } = useAppSelector(state => state.booking);
  const [name, setName] = useState('');
  const [email, setEmail] = useState('');
  // Pre-filled from the search or the salon page; still editable, because the
  // car someone looked up is not always the car they are bringing.
  const [plate, setPlate] = useState(params.get('regnr') ?? '');
  // The extras on offer for this service, fetched with the salon so the
  // names and prices shown are the server's; only ids go back (ADR 0017).
  const [addons, setAddons] = useState<Addon[]>([]);
  const [chosen, setChosen] = useState<number[]>([]);
  useEffect(() => {
    if (!slug) return;
    axios.get<ProviderDetail>(`/providers/${slug}`).then(
      response => setAddons(response.data.services.find(s => s.id === Number(serviceId))?.addons ?? []),
      () => setAddons([]),
    );
  }, [slug, serviceId]);
  const extrasTotal = addons.filter(a => chosen.includes(a.id)).reduce((sum, a) => sum + a.priceMinor, 0);
  const total = shownPrice !== undefined ? shownPrice + extrasTotal : undefined;

  // A fresh key per visit, so a customer who comes back to book a different
  // time is not silently replaying the previous attempt.
  useEffect(() => {
    dispatch(checkoutReset());
  }, [dispatch, serviceId, start]);

  const submit = (event: React.FormEvent) => {
    event.preventDefault();
    dispatch(book({
      serviceId: Number(serviceId),
      slotStart: start,
      customerName: name,
      customerEmail: email,
      ...(asksVehicle ? { registrationNumber: plate } : {}),
      ...(total !== undefined ? { quotedPriceMinor: total } : {}),
      ...(chosen.length > 0 ? { addonIds: chosen } : {}),
    }));
  };

  if (outcome?.state === 'CONFIRMED') {
    return (
      <div className="alert alert-success">
        <h1 className="h5">Bokat</h1>
        <p className="mb-1">
          {formatDay(start)} kl {formatTime(start)}. Bekräftelse skickas till {email}.
        </p>
        <Link to="/">Sök fler tider</Link>
      </div>
    );
  }

  if (outcome?.state === 'AWAITING_PAYMENT') {
    return (
      <div className="alert alert-info">
        <h1 className="h5">Öppna Swish för att betala</h1>
        <p className="mb-1">
          Tiden {formatDay(start)} kl {formatTime(start)} är reserverad medan du betalar.
        </p>
        <p className="small mb-0">
          Reservationen släpps automatiskt om betalningen inte blir klar.
        </p>
      </div>
    );
  }

  if (outcome && ['REFUSED', 'VERIFY_FAILED'].includes(outcome.state)) {
    return (
      <div className="alert alert-warning">
        <h1 className="h5">Tiden hann bli bokad</h1>
        <p className="mb-1">Någon annan tog den medan du fyllde i. Välj gärna en annan tid.</p>
        {slug && <Link to={`/salong/${slug}`}>Tillbaka till salongen</Link>}
      </div>
    );
  }

  if (outcome && ['CHARGE_FAILED', 'CONFIRM_FAILED'].includes(outcome.state)) {
    return (
      <div className="alert alert-warning">
        <h1 className="h5">Betalningen gick inte igenom</h1>
        <p className="mb-1">Tiden är släppt och inga pengar har dragits.</p>
        {slug && <Link to={`/salong/${slug}`}>Försök igen</Link>}
      </div>
    );
  }

  if (outcome?.state === 'NEEDS_ATTENTION') {
    // Deliberately not "try again". Something is genuinely unresolved and a
    // retry could make it worse; a human is already looking.
    return (
      <div className="alert alert-danger">
        <h1 className="h5">Vi behöver kontrollera din bokning</h1>
        <p className="mb-0">
          Något gick fel på vägen och vi tittar på det. Vi hör av oss till {email}. Boka inte om
          samma tid än.
        </p>
      </div>
    );
  }

  return (
    <div className="row justify-content-center">
      <div className="col-lg-6">
        <h1 className="h4">Bekräfta bokning</h1>
        <p className="text-muted">
          {formatDay(start)} kl {formatTime(start)}
        </p>

        {error && priceNow === null && <div className="alert alert-warning">{error}</div>}
        {priceNow !== null && (
          <div className="alert alert-warning">
            Priset har ändrats sedan du valde tiden: nu {formatPrice(priceNow, 'SEK')}.
            {slug && <> <Link to={`/salong/${slug}`}>Tillbaka till salongen</Link> för att boka till det nya priset.</>}
          </div>
        )}
        {addons.length > 0 && (
          <fieldset className="mb-3">
            <legend className="h6">Tillval</legend>
            {addons.map(addon => (
              <div className="form-check" key={addon.id}>
                <input className="form-check-input" type="checkbox" id={`addon-${addon.id}`}
                  checked={chosen.includes(addon.id)}
                  onChange={event => setChosen(event.target.checked
                    ? [...chosen, addon.id] : chosen.filter(id => id !== addon.id))} />
                <label className="form-check-label" htmlFor={`addon-${addon.id}`}>
                  {addon.name} <span className="text-muted">+ {formatPrice(addon.priceMinor, 'SEK')}</span>
                </label>
              </div>
            ))}
          </fieldset>
        )}
        {total !== undefined && priceNow === null && (
          <p className="fw-semibold">Att betala: {formatPrice(total, 'SEK')}</p>
        )}

        <form onSubmit={submit}>
          <div className="mb-3">
            <label className="form-label" htmlFor="name">Namn</label>
            <input id="name" className="form-control" required value={name}
              onChange={event => setName(event.target.value)} />
          </div>
          <div className="mb-3">
            <label className="form-label" htmlFor="email">E-post</label>
            <input id="email" type="email" className="form-control" required value={email}
              onChange={event => setEmail(event.target.value)} />
            <div className="form-text">Hit skickas bekräftelsen.</div>
          </div>
          {asksVehicle && (
            <div className="mb-3">
              <label className="form-label" htmlFor="plate">Registreringsnummer</label>
              <input id="plate" className="form-control" required value={plate}
                autoComplete="off" placeholder="ABC 123"
                onChange={event => setPlate(event.target.value)} />
              <div className="form-text">Så att verkstaden vet vilken bil som kommer.</div>
            </div>
          )}

          <button className="btn btn-primary" type="submit" disabled={submitting}>
            {submitting ? 'Bokar…' : 'Boka och betala'}
          </button>
        </form>
      </div>
    </div>
  );
};

export default Checkout;
