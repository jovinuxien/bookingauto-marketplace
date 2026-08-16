import React, { useEffect } from 'react';
import { Link, useSearchParams } from 'react-router-dom';

import { useAppDispatch, useAppSelector } from 'app/config/store';
import { runSearch } from 'app/shared/reducers/search.reducer';
import { DEFAULT_RADIUS_METRES, FALLBACK_POSITION } from 'app/config/constants';
import { addDays, formatDay, formatDistance, formatPrice, formatTime, todayInZone }
  from 'app/shared/util/format';
import type { SearchHit } from 'app/shared/model/marketplace.model';

const PARTS = [
  { value: 'ANY', label: 'När som helst' },
  { value: 'MORNING', label: 'Förmiddag' },
  { value: 'AFTERNOON', label: 'Eftermiddag' },
  { value: 'EVENING', label: 'Kväll' },
] as const;

/**
 * Results, read from the availability index.
 *
 * The criteria live in the URL rather than only in the store, so a search can
 * be shared, bookmarked and reloaded — which for a marketplace is most of how
 * people arrive.
 */
const SearchResults = () => {
  const dispatch = useAppDispatch();
  const [params, setParams] = useSearchParams();
  const { hits, loading, error, searched } = useAppSelector(state => state.search);

  const lat = Number(params.get('lat') ?? FALLBACK_POSITION.lat);
  const lon = Number(params.get('lon') ?? FALLBACK_POSITION.lon);
  const day = params.get('day') ?? todayInZone();
  const when = (params.get('when') ?? 'ANY') as SearchCriteriaWhen;
  const radius = Number(params.get('radius') ?? DEFAULT_RADIUS_METRES);

  useEffect(() => {
    dispatch(runSearch({ lat, lon, radius, day, when }));
  }, [dispatch, lat, lon, radius, day, when]);

  const update = (changes: Record<string, string>) => {
    const next = new URLSearchParams(params);
    Object.entries(changes).forEach(([key, value]) => next.set(key, value));
    setParams(next);
  };

  return (
    <>
      <div className="d-flex flex-wrap gap-2 align-items-center mb-4">
        <div className="btn-group">
          <button className="btn btn-outline-secondary btn-sm"
            onClick={() => update({ day: addDays(day, -1) })}>
            ‹
          </button>
          <span className="btn btn-light btn-sm disabled text-dark">{formatDay(`${day}T12:00:00Z`)}</span>
          <button className="btn btn-outline-secondary btn-sm"
            onClick={() => update({ day: addDays(day, 1) })}>
            ›
          </button>
        </div>

        <select className="form-select form-select-sm w-auto" value={when}
          onChange={event => update({ when: event.target.value })}>
          {PARTS.map(part => (
            <option key={part.value} value={part.value}>{part.label}</option>
          ))}
        </select>

        <select className="form-select form-select-sm w-auto" value={String(radius)}
          onChange={event => update({ radius: event.target.value })}>
          <option value="2000">Inom 2 km</option>
          <option value="5000">Inom 5 km</option>
          <option value="20000">Inom 20 km</option>
        </select>
      </div>

      {loading && <p className="text-muted">Söker…</p>}
      {error && <div className="alert alert-warning">{error}</div>}
      {!loading && !error && searched && hits.length === 0 && (
        <p className="text-muted">Inga lediga tider den här dagen. Prova en annan dag.</p>
      )}

      <div className="row g-3">
        {hits.map(hit => <ResultCard key={`${hit.providerId}-${hit.serviceName}`} hit={hit} />)}
      </div>
    </>
  );
};

type SearchCriteriaWhen = 'ANY' | 'MORNING' | 'AFTERNOON' | 'EVENING';

const ResultCard = ({ hit }: { hit: SearchHit }) => (
  <div className="col-12">
    <div className="card">
      <div className="card-body d-flex justify-content-between align-items-start flex-wrap gap-3">
        <div>
          <h2 className="h6 mb-1">
            <Link to={`/salong/${hit.slug}`}>{hit.name}</Link>
          </h2>
          <div className="small text-muted">
            {hit.city} · {formatDistance(hit.distanceMetres)}
          </div>
          <div className="mt-2">
            {hit.serviceName} · {hit.durationMinutes} min ·{' '}
            <strong>{formatPrice(hit.priceMinor, hit.currency)}</strong>
          </div>
        </div>

        <div className="text-end">
          <div className="small text-muted">{hit.freeSlots} lediga tider</div>
          {hit.firstFreeAt && (
            <div className="fw-semibold">Först {formatTime(hit.firstFreeAt)}</div>
          )}
          <Link className="btn btn-sm btn-primary mt-2" to={`/salong/${hit.slug}`}>
            Visa tider
          </Link>
        </div>
      </div>
      <StalenessNote seconds={hit.indexAgeSeconds} />
    </div>
  </div>
);

/**
 * Says so when a result is old.
 *
 * Search reads an index that is allowed to be stale, and the exact times are
 * only confirmed against the salon's calendar on the next page. Saying that out
 * loud when it starts to matter is cheaper than a customer discovering it.
 */
const StalenessNote = ({ seconds }: { seconds: number }) => {
  if (seconds < 900) {
    return null;
  }
  return (
    <div className="card-footer py-1 small text-muted">
      Uppdaterades för {Math.round(seconds / 60)} minuter sedan — tiderna bekräftas i nästa steg.
    </div>
  );
};

export default SearchResults;
