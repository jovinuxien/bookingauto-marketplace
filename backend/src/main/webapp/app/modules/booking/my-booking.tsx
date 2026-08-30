import React, { useEffect, useState } from 'react';
import { Link, useSearchParams } from 'react-router-dom';

import { useAppDispatch, useAppSelector } from 'app/config/store';
import { cancelBooking, loadBooking } from 'app/shared/reducers/my-booking.reducer';
import { formatDay, formatPrice, formatTime } from 'app/shared/util/format';

/**
 * Where the link in a confirmation email lands.
 *
 * Unlike the signup verification page, this one does nothing on arrival except
 * read. The click in the mail client meant "show me my booking"; cancelling is
 * a separate decision and gets a separate, confirmed action — the one thing on
 * this page that cannot be undone.
 */
const MyBooking = () => {
  const dispatch = useAppDispatch();
  const [params] = useSearchParams();
  const token = params.get('token');

  const { loading, booking, error, cancelling, cancelError, justCancelled } = useAppSelector(
    state => state.myBooking
  );

  /** The confirm step. A cancellation is irreversible and one click is not enough. */
  const [confirming, setConfirming] = useState(false);

  useEffect(() => {
    if (token) {
      dispatch(loadBooking(token));
    }
  }, [token, dispatch]);

  if (!token) {
    return (
      <Panel title="Länken är ofullständig">
        Kopiera hela adressen från mejlet. Den slutar med en lång rad tecken.
      </Panel>
    );
  }

  if (loading && !booking) {
    return <Panel title="Hämtar din bokning…">Ett ögonblick.</Panel>;
  }

  if (error && !booking) {
    return (
      <Panel title="Vi hittar ingen bokning">
        <p>{error}</p>
        <Link className="btn btn-outline-secondary btn-sm" to="/">
          Till startsidan
        </Link>
      </Panel>
    );
  }

  if (!booking) {
    return null;
  }

  const cancelled = booking.status !== 'confirmed';

  return (
    <div className="row justify-content-center">
      <div className="col-md-7">
        <h1 className="h4 mb-1">
          {cancelled ? 'Avbokad tid' : 'Din tid'} hos {booking.providerName}
        </h1>
        <p className="text-muted small mb-4">{booking.city}</p>

        {justCancelled && (
          <div className="alert alert-success">
            {booking.status === 'refunded' ? (
              <>
                Tiden är avbokad och vi betalar tillbaka{' '}
                {formatPrice(booking.priceMinor, booking.currency)}. Återbetalningen syns
                normalt inom några minuter, beroende på bank.
              </>
            ) : (
              <>Tiden är avbokad och salongen har fått besked.</>
            )}
          </div>
        )}

        {/*
          The one state where something is genuinely unresolved. Said plainly and
          without a "try again", because a second attempt cannot help and would
          only invite someone to keep pressing.
        */}
        {booking.needsAttention && (
          <div className="alert alert-warning">
            Tiden är avbokad, men återbetalningen har inte gått igenom än. Vi håller på
            med den och hör av oss.
          </div>
        )}

        <dl className="row mb-4">
          <Row label="Behandling" value={booking.serviceName} />
          <Row label="Dag" value={formatDay(booking.startsAt)} />
          <Row
            label="Tid"
            value={`${formatTime(booking.startsAt)}–${formatTime(booking.endsAt)}`}
          />
          <Row label="Pris" value={formatPrice(booking.priceMinor, booking.currency)} />
          <Row label="Bokad av" value={booking.customerName} />
          {booking.registrationNumber && <Row label="Fordon" value={booking.registrationNumber} />}
          <Row label="Status" value={<Status status={booking.status} />} />
        </dl>

        {cancelError && <div className="alert alert-warning">{cancelError}</div>}

        {booking.cancellable && !confirming && (
          <>
            {/*
              What it costs is stated before the button, never after. A customer
              who finds out about the cutoff on the confirmation screen has been
              told at the moment it is least useful to them.
            */}
            <p className="text-muted small">
              {booking.refundable ? (
                <>
                  Avbokar du före {formatDay(booking.freeUntil)} kl{' '}
                  {formatTime(booking.freeUntil)} får du tillbaka hela beloppet.
                </>
              ) : (
                <>
                  Det är mindre än {booking.cutoffHours} timmar kvar till besöket, så
                  beloppet betalas inte tillbaka. Tiden blir ledig för någon annan.
                </>
              )}
            </p>
            <button
              className="btn btn-outline-danger"
              type="button"
              onClick={() => setConfirming(true)}
            >
              Avboka tiden
            </button>
          </>
        )}

        {booking.cancellable && confirming && (
          <div className="card card-body bg-light">
            <p className="mb-3">
              {booking.refundable
                ? 'Avboka tiden och få tillbaka hela beloppet?'
                : `Avboka tiden? Beloppet ${formatPrice(booking.priceMinor, booking.currency)} betalas inte tillbaka.`}
            </p>
            <div className="d-flex gap-2">
              <button
                className="btn btn-danger"
                type="button"
                disabled={cancelling}
                onClick={() => dispatch(cancelBooking(token))}
              >
                {cancelling ? 'Avbokar…' : 'Ja, avboka'}
              </button>
              <button
                className="btn btn-outline-secondary"
                type="button"
                disabled={cancelling}
                onClick={() => setConfirming(false)}
              >
                Behåll tiden
              </button>
            </div>
          </div>
        )}

        {!booking.cancellable && !cancelled && (
          <p className="text-muted small">
            Tiden har redan börjat och går inte att avboka här. Kontakta salongen.
          </p>
        )}

        {cancelled && !justCancelled && (
          <Link className="btn btn-outline-secondary btn-sm" to="/">
            Boka en ny tid
          </Link>
        )}
      </div>
    </div>
  );
};

const Row = ({ label, value }: { label: string; value: React.ReactNode }) => (
  <>
    <dt className="col-sm-4 fw-normal text-muted">{label}</dt>
    <dd className="col-sm-8">{value}</dd>
  </>
);

const Status = ({ status }: { status: string }) => {
  const [text, className] =
    status === 'confirmed'
      ? ['Bokad', 'text-success']
      : status === 'refunded'
        ? ['Avbokad, återbetald', 'text-muted']
        : ['Avbokad', 'text-muted'];

  return <span className={className}>{text}</span>;
};

const Panel = ({ title, children }: { title: string; children: React.ReactNode }) => (
  <div className="row justify-content-center">
    <div className="col-md-6">
      <h1 className="h4 mb-3">{title}</h1>
      <div className="text-muted">{children}</div>
    </div>
  </div>
);

export default MyBooking;
