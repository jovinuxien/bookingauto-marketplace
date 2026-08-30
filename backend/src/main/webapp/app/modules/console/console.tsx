import React, { useEffect } from 'react';
import Pricing from 'app/modules/console/pricing';

import { useAppDispatch, useAppSelector } from 'app/config/store';
import { loadConsole } from 'app/shared/reducers/console.reducer';
import { formatDay, formatPrice, formatTime } from 'app/shared/util/format';
import type { ConsoleSummary } from 'app/shared/model/console.model';

/**
 * What a salon sees.
 *
 * Ordered by what someone would want to know on opening it: whether anything is
 * wrong, then whether they can be paid, then who is coming.
 */
const Console = () => {
  const dispatch = useAppDispatch();
  const { summary, bookings, attention, loading, error } = useAppSelector(state => state.console);

  useEffect(() => {
    dispatch(loadConsole());
  }, [dispatch]);

  if (loading && !summary) {
    return <p className="text-muted">Hämtar…</p>;
  }

  if (error) {
    return <div className="alert alert-warning">{error}</div>;
  }

  return (
    <>
      <h1 className="h4">{summary?.name}</h1>

      {attention.length > 0 && (
        <div className="alert alert-danger">
          <strong>{attention.length} bokning(ar) behöver kontrolleras.</strong>
          <ul className="mb-0 mt-2 small">
            {attention.map(item => (
              <li key={item.id}>
                {formatDay(item.slotStart)} kl {formatTime(item.slotStart)} — {item.customerEmail}
                {item.failure ? `: ${item.failure}` : ''}
              </li>
            ))}
          </ul>
        </div>
      )}

      {summary && <Payability summary={summary} />}

      <div className="row g-3 my-3">
        <Stat label="Kommande bokningar" value={String(summary?.upcomingCount ?? 0)} />
        <Stat label="Intjänat" value={formatPrice(summary?.earnedMinor ?? 0, 'SEK')} />
        <Stat label="Plattformsavgift" value={formatPrice(summary?.commissionMinor ?? 0, 'SEK')} />
        <Stat label="Tjänster" value={String(summary?.activeServices ?? 0)} />
      </div>

      <h2 className="h6 mt-4">Kommande</h2>
      {bookings.length === 0 && <p className="text-muted">Inga bokningar ännu.</p>}
      {bookings.length > 0 && (
        <div className="table-responsive">
          <table className="table table-sm align-middle">
            <thead>
              <tr>
                <th>När</th><th>Tjänst</th><th>Kund</th>
                <th className="text-end">Pris</th><th className="text-end">Avgift</th>
              </tr>
            </thead>
            <tbody>
              {bookings.map(booking => (
                <tr key={booking.id}>
                  <td>{formatDay(booking.startsAt)} {formatTime(booking.startsAt)}</td>
                  <td>{booking.serviceName}</td>
                  <td>
                    {booking.customerName}
                    <div className="small text-muted">{booking.customerEmail}</div>
                    {booking.registrationNumber && (
                      <div className="small">
                        <span className="font-monospace">{booking.registrationNumber}</span>
                        {booking.vehicle && <span className="text-muted"> · {booking.vehicle}</span>}
                      </div>
                    )}
                  </td>
                  <td className="text-end">{formatPrice(booking.priceMinor, booking.currency)}</td>
                  <td className="text-end text-muted">
                    {formatPrice(booking.commissionMinor, booking.currency)}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}

      <Pricing />
    </>
  );
};

/**
 * Why the salon is or is not sellable.
 *
 * The two blockers are reported separately because only one of them is
 * actionable by the salon. Telling someone to "complete setup" when the ball is
 * with Stripe wastes their afternoon.
 */
const Payability = ({ summary }: { summary: ConsoleSummary }) => {
  if (summary.status === 'active') {
    return <div className="alert alert-success py-2 small mb-0">Din salong är sökbar.</div>;
  }
  if (!summary.payoutsEnabled) {
    return (
      <div className="alert alert-warning py-2 small mb-0">
        Stripe har inte godkänt utbetalningar än. Vi kan inte visa dig i sök förrän det är klart —
        du behöver inte göra något mer just nu.
      </div>
    );
  }
  if (summary.activeServices === 0) {
    return (
      <div className="alert alert-warning py-2 small mb-0">
        Du har inga tjänster ännu. Lägg upp dem i din kalender, så hämtar vi in dem.
      </div>
    );
  }
  return <div className="alert alert-secondary py-2 small mb-0">Salongen är inte publicerad än.</div>;
};

const Stat = ({ label, value }: { label: string; value: string }) => (
  <div className="col-6 col-lg-3">
    <div className="border rounded p-3 h-100">
      <div className="small text-muted">{label}</div>
      <div className="fs-5 fw-semibold">{value}</div>
    </div>
  </div>
);

export default Console;
