import React, { useEffect, useState } from 'react';
import { useNavigate, useParams, useSearchParams } from 'react-router-dom';
import { RegnrBox } from 'app/modules/vehicles/regnr-box';
import { Stars } from 'app/shared/components/stars';
import axios from 'app/config/axiosinstance';
import type { ProviderReviews } from 'app/shared/model/review.model';

import { useAppDispatch, useAppSelector } from 'app/config/store';
import { loadProvider, loadSlots } from 'app/shared/reducers/provider.reducer';
import { addDays, formatDay, formatPrice, formatTime, todayInZone } from 'app/shared/util/format';

/**
 * The salon, and the times it can actually offer.
 *
 * This page is where the index stops being trusted. The slot list comes from
 * the salon's calendar, not from what search said, and that is deliberate: the
 * customer is about to pick a specific minute, and being approximately right
 * about that is worse than being slow.
 */
const ProviderPage = () => {
  const { slug } = useParams<{ slug: string }>();
  const dispatch = useAppDispatch();
  const navigate = useNavigate();
  const [params, setParams] = useSearchParams();

  const { provider, slots, loadingProvider, loadingSlots, error } =
    useAppSelector(state => state.provider);

  const day = params.get('day') ?? todayInZone();
  // Carried from the search page, or typed here; carried on to checkout.
  const regnr = params.get('regnr') ?? undefined;
  const [serviceId, setServiceId] = useState<number | null>(null);
  // Fetched beside the provider, not inside it: reviews change on their own
  // schedule and belong to their own module.
  const [reviews, setReviews] = useState<ProviderReviews | null>(null);

  useEffect(() => {
    if (slug) {
      dispatch(loadProvider(slug));
      axios.get<ProviderReviews>(`/reviews/${slug}`).then(r => setReviews(r.data), () => setReviews(null));
    }
  }, [dispatch, slug]);

  // Pick the first service automatically. A salon with one service should not
  // make anyone choose it.
  useEffect(() => {
    if (provider && provider.services.length > 0 && serviceId === null) {
      setServiceId(provider.services[0].id);
    }
  }, [provider, serviceId]);

  useEffect(() => {
    if (serviceId !== null) {
      dispatch(loadSlots({ serviceId, day }));
    }
  }, [dispatch, serviceId, day]);

  const setDay = (next: string) => {
    const updated = new URLSearchParams(params);
    updated.set('day', next);
    setParams(updated);
  };

  const setRegnr = (plate: string) => {
    if (plate !== regnr) {
      const updated = new URLSearchParams(params);
      updated.set('regnr', plate);
      setParams(updated);
    }
  };

  if (loadingProvider) {
    return <p className="text-muted">Hämtar…</p>;
  }

  if (!provider) {
    return <div className="alert alert-warning">{error ?? 'Salongen hittades inte.'}</div>;
  }

  const service = provider.services.find(candidate => candidate.id === serviceId);

  return (
    <>
      <h1 className="h3">{provider.name}</h1>
      <p className="text-muted">
        {provider.addressLine ? `${provider.addressLine}, ` : ''}
        {provider.city}
      </p>
      {reviews && reviews.summary.count > 0 && (
        <p className="mb-2"><Stars average={reviews.summary.average} count={reviews.summary.count} /></p>
      )}
      {provider.description && <p>{provider.description}</p>}

      <h2 className="h6 mt-4">Tjänster</h2>
      <div className="list-group mb-4">
        {provider.services.map(candidate => (
          <button
            key={candidate.id}
            type="button"
            className={`list-group-item list-group-item-action d-flex justify-content-between ${
              candidate.id === serviceId ? 'active' : ''
            }`}
            onClick={() => setServiceId(candidate.id)}
          >
            <span>
              {candidate.name} · {candidate.durationMinutes} min
            </span>
            <span>
              {formatPrice(candidate.priceMinor, candidate.currency)}
              {candidate.pricedForVehicle && (
                <small className="d-block text-end opacity-75">för din bil{candidate.priceLabel ? ` · ${candidate.priceLabel}` : ''}</small>
              )}
            </span>
          </button>
        ))}
      </div>

      {service?.asksVehicle && (
        <RegnrBox initial={regnr} onPlate={setRegnr} />
      )}

      <div className="d-flex align-items-center gap-2 mb-3">
        <div className="btn-group">
          <button className="btn btn-outline-secondary btn-sm" onClick={() => setDay(addDays(day, -1))}>
            ‹
          </button>
          <span className="btn btn-light btn-sm disabled text-dark">
            {formatDay(`${day}T12:00:00Z`)}
          </span>
          <button className="btn btn-outline-secondary btn-sm" onClick={() => setDay(addDays(day, 1))}>
            ›
          </button>
        </div>
      </div>

      {loadingSlots && <p className="text-muted">Hämtar tider från kalendern…</p>}
      {error && !loadingSlots && <div className="alert alert-warning">{error}</div>}

      {!loadingSlots && slots && slots.starts.length === 0 && (
        <p className="text-muted">Inga lediga tider den här dagen.</p>
      )}

      {!loadingSlots && slots && slots.starts.length > 0 && service && (
        <div className="d-flex flex-wrap gap-2">
          {slots.starts.map(start => (
            <button
              key={start}
              className="btn btn-outline-primary"
              onClick={() =>
                navigate(`/boka/${service.id}?start=${encodeURIComponent(start)}&slug=${provider.slug}${service.asksVehicle ? '&fordon=1' : ''}${regnr ? `&regnr=${encodeURIComponent(regnr)}` : ''}&pris=${service.priceMinor}`)
              }
            >
              {formatTime(start)}
            </button>
          ))}
        </div>
      )}

      <RecentReviews reviews={reviews} />
    </>
  );
};

export default ProviderPage;

/** What the last few customers said. Below the times: people book first and read second. */
export const RecentReviews = ({ reviews }: { reviews: ProviderReviews | null }) => {
  if (!reviews || reviews.recent.length === 0) {
    return null;
  }
  return (
    <>
      <h2 className="h6 mt-5">Omdömen</h2>
      <ul className="list-unstyled">
        {reviews.recent.map(review => (
          <li key={review.bookingId} className="border-bottom py-2">
            <div className="small">
              <span aria-hidden="true">{'★'.repeat(review.rating)}{'☆'.repeat(5 - review.rating)}</span>
              <span className="visually-hidden">{review.rating} av 5</span>
              <span className="text-muted"> · {review.author} · {formatDay(review.createdAt)}</span>
            </div>
            {review.comment && <p className="mb-0">{review.comment}</p>}
          </li>
        ))}
      </ul>
    </>
  );
};
