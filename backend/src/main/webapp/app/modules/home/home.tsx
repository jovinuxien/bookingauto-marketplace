import React, { useState } from 'react';
import { useNavigate } from 'react-router-dom';

import { FALLBACK_POSITION } from 'app/config/constants';
import { todayInZone } from 'app/shared/util/format';

/**
 * The entry point: where and when.
 *
 * Geolocation is offered but never required. A permission prompt on first load
 * is the fastest way to lose a visitor, so the search works from a sensible
 * default and asking is something the customer chooses to do.
 */
const Home = () => {
  const navigate = useNavigate();
  const [locating, setLocating] = useState(false);

  const search = (lat: number, lon: number) => {
    const params = new URLSearchParams({
      lat: String(lat),
      lon: String(lon),
      day: todayInZone(),
      when: 'ANY',
    });
    navigate(`/sok?${params.toString()}`);
  };

  const useMyPosition = () => {
    if (!navigator.geolocation) {
      search(FALLBACK_POSITION.lat, FALLBACK_POSITION.lon);
      return;
    }
    setLocating(true);
    navigator.geolocation.getCurrentPosition(
      position => {
        setLocating(false);
        search(position.coords.latitude, position.coords.longitude);
      },
      // Denied or unavailable is not an error worth showing. The customer gets
      // results either way; they are just centred on the city instead.
      () => {
        setLocating(false);
        search(FALLBACK_POSITION.lat, FALLBACK_POSITION.lon);
      },
      { timeout: 8000 }
    );
  };

  return (
    <div className="row justify-content-center">
      <div className="col-lg-7">
        <h1 className="h2 mb-3">Hitta en tid</h1>
        <p className="text-muted">
          Sök lediga tider hos salonger och kliniker nära dig. Tiderna kommer från deras egna
          kalendrar.
        </p>

        <div className="d-flex gap-2 mt-4">
          <button className="btn btn-primary" onClick={useMyPosition} disabled={locating}>
            {locating ? 'Hämtar position…' : 'Nära mig'}
          </button>
          <button
            className="btn btn-outline-secondary"
            onClick={() => search(FALLBACK_POSITION.lat, FALLBACK_POSITION.lon)}
          >
            Sök i Stockholm
          </button>
        </div>
      </div>
    </div>
  );
};

export default Home;
