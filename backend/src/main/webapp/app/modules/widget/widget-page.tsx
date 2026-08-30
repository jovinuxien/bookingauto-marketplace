import React, { useEffect, useState } from 'react';
import { useParams } from 'react-router-dom';
import axios from 'app/config/axiosinstance';
import type { DaySlots, ProviderDetail } from 'app/shared/model/marketplace.model';
import { RegnrBox } from 'app/modules/vehicles/regnr-box';
import { addDays, formatDay, formatPrice, formatTime, todayInZone } from 'app/shared/util/format';

/**
 * The embedded storefront (ADR 0018).
 *
 * Runs inside an iframe on the workshop's own site, so it is deliberately
 * small: services, live times, the plate for vehicle services — and the
 * booking itself opens the marketplace checkout in a new tab, because an
 * iframe on someone else's origin is where payments go to fail. The
 * hand-off carries kanal=widget so the workshop can see what its site
 * brought in.
 */
const WidgetPage = () => {
  const { slug } = useParams<{ slug: string }>();
  const [provider, setProvider] = useState<ProviderDetail | null>(null);
  const [failed, setFailed] = useState(false);
  const [serviceId, setServiceId] = useState<number | null>(null);
  const [day, setDay] = useState(todayInZone());
  const [slots, setSlots] = useState<DaySlots | null>(null);
  const [regnr, setRegnr] = useState<string | undefined>(undefined);

  useEffect(() => {
    if (!slug) return;
    const query = regnr ? `?regnr=${encodeURIComponent(regnr)}` : '';
    axios.get<ProviderDetail>(`/providers/${slug}${query}`).then(
      response => {
        setProvider(response.data);
        setServiceId(current => current ?? response.data.services[0]?.id ?? null);
      },
      () => setFailed(true),
    );
  }, [slug, regnr]);

  useEffect(() => {
    if (serviceId !== null) {
      setSlots(null);
      axios.get<DaySlots>(`/services/${serviceId}/slots`, { params: { day } }).then(
        response => setSlots(response.data),
        () => setSlots(serviceId === null ? null : { serviceId, day, starts: [] }),
      );
    }
  }, [serviceId, day]);

  if (failed) {
    return <p className="text-muted small p-3">Bokningen är inte tillgänglig just nu.</p>;
  }
  if (!provider) {
    return <p className="text-muted small p-3">Hämtar…</p>;
  }

  const service = provider.services.find(candidate => candidate.id === serviceId);

  const book = (start: string) => {
    if (!service) return;
    const params = new URLSearchParams({ start, slug: provider.slug, kanal: 'widget' });
    if (service.asksVehicle) params.set('fordon', '1');
    if (regnr) params.set('regnr', regnr);
    params.set('pris', String(service.priceMinor));
    // A new tab on our own origin: the payment happens where payments work.
    window.open(`${window.location.origin}/boka/${service.id}?${params}`, '_blank', 'noopener');
  };

  return (
    <div className="p-3">
      <div className="d-flex justify-content-between align-items-baseline">
        <strong>{provider.name}</strong>
        <span className="text-muted small">{provider.city}</span>
      </div>

      <select className="form-select form-select-sm my-2" value={serviceId ?? ''}
        aria-label="Tjänst" onChange={event => setServiceId(Number(event.target.value))}>
        {provider.services.map(candidate => (
          <option key={candidate.id} value={candidate.id}>
            {candidate.name} · {formatPrice(candidate.priceMinor, candidate.currency)}
            {candidate.pricedForVehicle ? ' (för din bil)' : ''}
          </option>
        ))}
      </select>

      {service?.asksVehicle && <RegnrBox compact initial={regnr} onPlate={setRegnr} />}

      <div className="d-flex align-items-center gap-2 my-2">
        <div className="btn-group">
          <button className="btn btn-outline-secondary btn-sm" type="button"
            onClick={() => setDay(addDays(day, -1))}>‹</button>
          <span className="btn btn-light btn-sm disabled text-dark">{formatDay(`${day}T12:00:00Z`)}</span>
          <button className="btn btn-outline-secondary btn-sm" type="button"
            onClick={() => setDay(addDays(day, 1))}>›</button>
        </div>
      </div>

      {!slots && <p className="text-muted small">Hämtar tider…</p>}
      {slots && slots.starts.length === 0 && (
        <p className="text-muted small">Inga lediga tider den här dagen.</p>
      )}
      {slots && slots.starts.length > 0 && (
        <div className="d-flex flex-wrap gap-2">
          {slots.starts.map(start => (
            <button key={start} className="btn btn-sm btn-outline-primary" type="button"
              onClick={() => book(start)}>
              {formatTime(start)}
            </button>
          ))}
        </div>
      )}

      <p className="text-muted small mt-3 mb-0">
        Bokningen öppnas i ett nytt fönster och betalas direkt online.
      </p>
    </div>
  );
};

export default WidgetPage;
