import React, { useEffect, useRef } from 'react';
import { Link, useSearchParams } from 'react-router-dom';

import { useAppDispatch, useAppSelector } from 'app/config/store';
import { verify } from 'app/shared/reducers/signup.reducer';

/**
 * Where the link in the email lands.
 *
 * Verifying on mount rather than behind a button. The click already happened —
 * it happened in the mail client — and asking someone to confirm that they
 * meant to click the thing they just clicked is a step that only exists to make
 * the code simpler.
 *
 * That does mean guarding against React's development double-invoke, which
 * would otherwise fire two verifications a millisecond apart. The server is
 * safe from it either way: claiming the token is a single conditional update,
 * so the second call finds nothing to claim. What it would produce is the
 * *second* call's answer on screen — "this link is being used" — for the person
 * whose first call is about to succeed.
 */
const Verify = () => {
  const dispatch = useAppDispatch();
  const [params] = useSearchParams();
  const token = params.get('token');
  const started = useRef(false);

  const { verifying, ready, verifyError, retryable } = useAppSelector(state => state.signup);

  useEffect(() => {
    if (token && !started.current) {
      started.current = true;
      dispatch(verify(token));
    }
  }, [token, dispatch]);

  if (!token) {
    return <Message title="Länken är ofullständig">
      Kopiera hela adressen från mejlet, eller <Link to="/registrera">registrera dig igen</Link>.
    </Message>;
  }

  if (verifying) {
    return <Message title="Skapar din salong…">Det tar några sekunder.</Message>;
  }

  if (verifyError) {
    return (
      <Message title="Det gick inte">
        <p>{verifyError}</p>
        {retryable ? (
          <button className="btn btn-outline-secondary" onClick={() => dispatch(verify(token))}>
            Försök igen
          </button>
        ) : (
          <Link className="btn btn-outline-secondary" to="/registrera">
            Registrera dig igen
          </Link>
        )}
      </Message>
    );
  }

  if (!ready) {
    return null;
  }

  return (
    <div className="row justify-content-center">
      <div className="col-md-7">
        <h1 className="h4 mb-3">{ready.salonName} är registrerad</h1>
        <p className="text-muted">
          Två saker återstår, och båda måste göras av dig. Salongen blir sökbar när de är klara.
        </p>

        <ol className="mt-4">
          <li className="mb-4">
            <strong>Verifiera dig hos Stripe.</strong>
            <p className="text-muted small mb-2">
              Vi kan inte betala ut pengar till salongen förrän det är klart.
            </p>
            <a className="btn btn-primary btn-sm" href={ready.kycUrl} rel="noreferrer">
              Fortsätt till Stripe
            </a>
          </li>

          <li className="mb-4">
            <strong>Lägg upp dina tjänster i Cal.</strong>
            <p className="text-muted small mb-2">
              Kalendern sköts i Cal. Där lägger du upp vad du säljer, hur lång tid det tar och vad
              det kostar.
            </p>

            {/*
              The Cal password is shown here and sent once by email, and is never
              stored in plain text on our side. It is a separate password from
              the one just chosen, deliberately: reusing the console password on
              a third-party system would make one breach into two.
            */}
            {ready.calPassword ? (
              <div className="card card-body bg-light">
                <div className="small text-muted">Användarnamn</div>
                <code>{ready.calUsername}</code>
                <div className="small text-muted mt-2">Lösenord</div>
                <code>{ready.calPassword}</code>
                <div className="small text-muted mt-2">
                  Spara det här. Vi visar det bara en gång, och skickar det i mejlet.
                </div>
              </div>
            ) : (
              <p className="small text-muted">
                Du har sedan tidigare ett Cal-konto med användarnamnet <code>{ready.calUsername}</code>.
              </p>
            )}
          </li>
        </ol>

        <p className="text-muted small">
          Salongens adress hos oss blir <code>/salong/{ready.slug}</code>.
        </p>

        <Link className="btn btn-outline-secondary" to="/logga-in">
          Logga in på konsolen
        </Link>
      </div>
    </div>
  );
};

const Message = ({ title, children }: { title: string; children: React.ReactNode }) => (
  <div className="row justify-content-center">
    <div className="col-md-6">
      <h1 className="h4 mb-3">{title}</h1>
      <div className="text-muted">{children}</div>
    </div>
  </div>
);

export default Verify;
