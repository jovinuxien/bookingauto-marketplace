import React, { useEffect, useState } from 'react';
import { Link, useSearchParams } from 'react-router-dom';
import axios from 'app/config/axiosinstance';
import type { CategoryChoice } from 'app/shared/model/signup.model';
import { RegnrBox } from 'app/modules/vehicles/regnr-box';

import { useAppDispatch, useAppSelector } from 'app/config/store';
import { askSearch, interpretationDropped, runSearch } from 'app/shared/reducers/search.reducer';
import { DEFAULT_RADIUS_METRES, FALLBACK_POSITION } from 'app/config/constants';
import { addDays, formatDay, formatDistance, formatPrice, formatTime, todayInZone }
  from 'app/shared/util/format';
import type { SearchHit } from 'app/shared/model/marketplace.model';
import type { Interpretation } from 'app/shared/reducers/search.reducer';

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
  const { hits, loading, error, searched, interpretation, interpreting } =
    useAppSelector(state => state.search);

  const lat = Number(params.get('lat') ?? FALLBACK_POSITION.lat);
  const lon = Number(params.get('lon') ?? FALLBACK_POSITION.lon);
  const day = params.get('day') ?? todayInZone();
  const when = (params.get('when') ?? 'ANY') as SearchCriteriaWhen;
  const radius = Number(params.get('radius') ?? DEFAULT_RADIUS_METRES);
  const category = params.get('category') ?? undefined;
  const regnr = params.get('regnr') ?? undefined;

  // Which categories mean a car is coming. Fetched, not listed here, for the
  // reason ADR 0013 gives: the server has the one list. The plate box is
  // shown for those categories and for any search that already carries a
  // plate -- a person who typed one has told us what kind of search it is.
  const [vehicleCategories, setVehicleCategories] = useState<Set<string>>(new Set());
  useEffect(() => {
    axios.get<CategoryChoice[]>('/categories').then(
      response => setVehicleCategories(new Set(response.data.filter(c => c.vehicle).map(c => c.slug))),
      () => setVehicleCategories(new Set()),
    );
  }, []);
  const asksVehicle = regnr !== undefined || (category !== undefined && vehicleCategories.has(category));

  /**
   * The sentence is not in the URL, and that is deliberate.
   *
   * Every other criterion is, so a search can be shared and reloaded. This one
   * is metered: in the URL, a refresh or a back button would silently buy
   * another interpretation. Instead the sentence sets the filters once and the
   * filters are what the URL carries — which is also the shareable thing, since
   * what someone wants to send a friend is the search, not the phrasing.
   */
  const [question, setQuestion] = useState('');

  useEffect(() => {
    dispatch(runSearch({ lat, lon, radius, day, when, category }));
  }, [dispatch, lat, lon, radius, day, when, category]);

  const update = (changes: Record<string, string>) => {
    // A filter touched by hand means the results are no longer what we
    // understood, so the note comes down rather than taking credit for it.
    dispatch(interpretationDropped());
    const next = new URLSearchParams(params);
    Object.entries(changes).forEach(([key, value]) => next.set(key, value));
    setParams(next);
  };

  const ask = async (event: React.FormEvent) => {
    event.preventDefault();

    if (!question.trim()) {
      return;
    }

    try {
      const answer = await dispatch(askSearch({ q: question.trim(), lat, lon, radius })).unwrap();

      // What was understood lands in the controls above the results, so the
      // customer can correct it exactly the way they would have set it.
      const next = new URLSearchParams(params);
      next.set('day', answer.applied.day);
      next.set('when', answer.applied.partOfDay);
      next.set('radius', String(answer.applied.radiusMetres));
      if (answer.applied.categorySlug) {
        next.set('category', answer.applied.categorySlug);
      } else {
        next.delete('category');
      }
      setParams(next);
    } catch {
      // The endpoint is built not to fail; if the request itself did — a
      // timeout, an offline browser — the filters already on screen are still
      // a search, and showing an error instead would be the one outcome the
      // whole design refuses.
      dispatch(runSearch({ lat, lon, radius, day, when, category }));
    }
  };

  return (
    <>
      <form className="mb-3" onSubmit={ask}>
        <div className="input-group">
          <input
            className="form-control"
            type="search"
            value={question}
            maxLength={200}
            placeholder="Beskriv vad du söker — ”balayage på lördag eftermiddag”"
            aria-label="Sök med egna ord"
            onChange={event => setQuestion(event.target.value)}
          />
          <button className="btn btn-primary" type="submit" disabled={interpreting || !question.trim()}>
            {interpreting ? 'Tolkar…' : 'Sök'}
          </button>
        </div>
      </form>

      <Understood interpretation={interpretation} category={category}
        onClearCategory={() => {
          const next = new URLSearchParams(params);
          next.delete('category');
          dispatch(interpretationDropped());
          setParams(next);
        }} />

      {asksVehicle && (
        <div className="mb-3">
          <RegnrBox compact initial={regnr} onPlate={plate => {
            if (plate !== regnr) {
              const next = new URLSearchParams(params);
              next.set('regnr', plate);
              setParams(next);
            }
          }} />
        </div>
      )}

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
        {hits.map(hit => <ResultCard key={`${hit.providerId}-${hit.serviceName}`} hit={hit} regnr={regnr} />)}
      </div>
    </>
  );
};

type SearchCriteriaWhen = 'ANY' | 'MORNING' | 'AFTERNOON' | 'EVENING';

/**
 * What we understood, above the results it produced.
 *
 * Shown rather than logged. A filter the customer cannot see is one they cannot
 * correct, and an empty page under a filter nobody chose reads as a city with
 * no salons in it — so both what was applied and what was refused are on the
 * page, and the category is removable in one click.
 */
const Understood = ({ interpretation, category, onClearCategory }: {
  interpretation: Interpretation | null;
  category?: string;
  onClearCategory: () => void;
}) => {
  if (!interpretation) {
    return null;
  }

  const { summary, ignored } = interpretation;

  if (!summary && ignored.length === 0) {
    return null;
  }

  return (
    <div className="alert alert-light border d-flex flex-wrap gap-2 align-items-center py-2">
      {summary && <span className="small">{summary}</span>}

      {category && (
        <button type="button" className="btn btn-sm btn-outline-secondary py-0"
          onClick={onClearCategory}>
          {category} <span aria-hidden="true">✕</span>
          <span className="visually-hidden">Ta bort kategorifiltret</span>
        </button>
      )}

      {ignored.map(note => (
        <span key={note} className="small text-muted">{note}</span>
      ))}
    </div>
  );
};

// The plate follows the customer to the salon page, so it is typed once.
const providerPath = (slug: string, regnr?: string) =>
  regnr ? `/salong/${slug}?regnr=${encodeURIComponent(regnr)}` : `/salong/${slug}`;

const ResultCard = ({ hit, regnr }: { hit: SearchHit; regnr?: string }) => (
  <div className="col-12">
    <div className="card">
      <div className="card-body d-flex justify-content-between align-items-start flex-wrap gap-3">
        <div>
          <h2 className="h6 mb-1">
            <Link to={providerPath(hit.slug, regnr)}>{hit.name}</Link>
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
          <Link className="btn btn-sm btn-primary mt-2" to={providerPath(hit.slug, regnr)}>
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
