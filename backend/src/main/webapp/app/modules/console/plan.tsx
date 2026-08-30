import React, { useEffect, useState } from 'react';
import axios from 'app/config/axiosinstance';
import type { PlanView } from 'app/shared/model/console.model';
import { formatPrice } from 'app/shared/util/format';

/**
 * The price list, and where this provider sits on it (ADR 0020).
 *
 * What a plan changes is the commission and nothing else -- search stays
 * distance and time whatever anyone pays -- and the plan is moved by a
 * person, so the call to action is a conversation, not a button.
 */
const PlanCard = () => {
  const [view, setView] = useState<PlanView | null>(null);

  useEffect(() => {
    axios.get<PlanView>('/console/plan').then(r => setView(r.data), () => setView(null));
  }, []);

  if (!view) {
    return null;
  }

  return (
    <>
      <h2 className="h6 mt-5">Ert paket</h2>
      <div className="table-responsive" style={{ maxWidth: '34rem' }}>
        <table className="table table-sm align-middle mb-1">
          <thead>
            <tr><th>Paket</th><th className="text-end">Per månad</th><th className="text-end">Provision</th><th></th></tr>
          </thead>
          <tbody>
            {view.all.map(plan => (
              <tr key={plan.name} className={plan.name === view.current.name ? 'table-primary' : ''}>
                <td>{plan.label}</td>
                <td className="text-end">{plan.monthlyMinor === 0 ? '0 kr' : formatPrice(plan.monthlyMinor, 'SEK')}</td>
                <td className="text-end">{(plan.commissionBps / 100).toLocaleString('sv-SE')} %</td>
                <td className="small text-muted">{plan.name === view.current.name ? 'ert paket' : ''}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      <p className="text-muted small">
        Provisionen dras per bokning som i dag. Vill ni byta paket? Hör av er, så ordnar vi det —
        placeringen i sökresultaten påverkas aldrig av paketet.
      </p>
    </>
  );
};

export default PlanCard;
