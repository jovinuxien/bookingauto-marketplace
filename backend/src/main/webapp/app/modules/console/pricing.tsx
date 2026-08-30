import React, { useEffect, useState } from 'react';
import axios from 'app/config/axiosinstance';
import type { ApiError } from 'app/config/axios-interceptor';
import type { ConsoleQuote, NewPriceRule, ServicePricing } from 'app/shared/model/console.model';
import { formatPrice } from 'app/shared/util/format';

/**
 * Prices per car (ADR 0016 phase 3).
 *
 * Only services whose category asks for a vehicle get rules; a salon sees
 * nothing here. Each rule is one line the workshop already has in its
 * head -- "Volvo 2015–2019, 2 490 kr" -- and the quote box runs the same
 * matcher a customer meets, so a rule can be checked against a real plate
 * before a customer finds the mistake.
 */
const Pricing = () => {
  const [services, setServices] = useState<ServicePricing[] | null>(null);
  const [error, setError] = useState<string | null>(null);

  const load = () =>
    axios.get<ServicePricing[]>('/console/pricing').then(
      response => setServices(response.data.filter(s => s.service.asksVehicle)),
      () => setError('Kunde inte hämta prislistan.'),
    );

  useEffect(() => { void load(); }, []);

  if (services === null || services.length === 0) {
    return null;
  }

  return (
    <>
      <h2 className="h6 mt-5">Priser per bil</h2>
      <p className="text-muted small">
        Listpriset gäller när ingen regel passar. Den mest specifika regeln vinner; vid lika
        specificitet det lägre priset.
      </p>
      {error && <div className="alert alert-warning">{error}</div>}
      {services.map(entry => (
        <ServiceRules key={entry.service.id} entry={entry} onChange={load} />
      ))}
    </>
  );
};

const ServiceRules = ({ entry, onChange }: { entry: ServicePricing; onChange: () => void }) => {
  const { service, rules } = entry;
  const [form, setForm] = useState<Record<string, string>>({});
  const [problem, setProblem] = useState<string | null>(null);
  const [plate, setPlate] = useState('');
  const [quote, setQuote] = useState<ConsoleQuote | null>(null);

  const num = (key: string) => (form[key]?.trim() ? Number(form[key]) : undefined);

  const add = async (event: React.FormEvent) => {
    event.preventDefault();
    setProblem(null);
    const rule: NewPriceRule = {
      make: form.make, modelPrefix: form.modelPrefix, label: form.label,
      yearFrom: num('yearFrom'), yearTo: num('yearTo'),
      rimFrom: num('rimFrom'), rimTo: num('rimTo'),
      priceMinor: Math.round((num('price') ?? 0) * 100),
    };
    try {
      await axios.post(`/console/pricing/${service.id}/rules`, rule);
      setForm({});
      onChange();
    } catch (e) {
      const failure = e as ApiError;
      setProblem(typeof failure.data === 'string' ? failure.data : 'Regeln kunde inte sparas.');
    }
  };

  const remove = async (id: number) => {
    await axios.delete(`/console/pricing/rules/${id}`);
    onChange();
  };

  const ask = async (event: React.FormEvent) => {
    event.preventDefault();
    try {
      const response = await axios.get<ConsoleQuote>(`/console/pricing/${service.id}/quote`, { params: { regnr: plate } });
      setQuote(response.data);
    } catch {
      setQuote(null);
    }
  };

  const field = (key: string, placeholder: string, width = '7rem', type = 'text') => (
    <input className="form-control form-control-sm" style={{ width }} type={type} placeholder={placeholder}
      value={form[key] ?? ''} onChange={e => setForm({ ...form, [key]: e.target.value })} aria-label={placeholder} />
  );

  return (
    <div className="card mb-3">
      <div className="card-body">
        <div className="d-flex justify-content-between">
          <strong>{service.name}</strong>
          <span>Listpris {formatPrice(service.priceMinor, service.currency)}</span>
        </div>

        {rules.length > 0 && (
          <table className="table table-sm mt-2 mb-2">
            <tbody>
              {rules.map(rule => (
                <tr key={rule.id}>
                  <td>{rule.label}</td>
                  <td className="text-end">{formatPrice(rule.priceMinor, service.currency)}</td>
                  <td className="text-end">
                    <button className="btn btn-link btn-sm p-0" type="button" onClick={() => remove(rule.id)}>Ta bort</button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}

        <form className="d-flex flex-wrap gap-1 align-items-center mt-2" onSubmit={add}>
          {field('make', 'Märke')}
          {field('modelPrefix', 'Modell')}
          {field('yearFrom', 'År från', '5.5rem', 'number')}
          {field('yearTo', 'År till', '5.5rem', 'number')}
          {field('rimFrom', 'Tum från', '5.5rem', 'number')}
          {field('rimTo', 'Tum till', '5.5rem', 'number')}
          {field('price', 'Pris kr', '6rem', 'number')}
          {field('label', 'Etikett (valfri)', '10rem')}
          <button className="btn btn-sm btn-outline-primary" type="submit">Lägg till regel</button>
        </form>
        {problem && <div className="text-danger small mt-1">{problem}</div>}

        <form className="d-flex gap-1 align-items-center mt-3" onSubmit={ask}>
          <span className="small text-muted">Vad kostar det för</span>
          <input className="form-control form-control-sm font-monospace text-uppercase" style={{ width: '7rem' }}
            placeholder="ABC 123" value={plate} onChange={e => setPlate(e.target.value)} aria-label="Registreringsnummer att prova" />
          <button className="btn btn-sm btn-outline-secondary" type="submit" disabled={!plate.trim()}>Prova</button>
          {quote && (
            <span className="small ms-2">
              {quote.vehicle ?? (quote.registryUnavailable ? 'registret svarar inte' : 'okänd bil')}
              {quote.tyres ? ` · ${quote.tyres}` : ''} → <strong>{formatPrice(quote.quote.priceMinor, service.currency)}</strong>
              {quote.quote.forVehicle ? ` (${quote.quote.label})` : ' (listpris)'}
            </span>
          )}
        </form>
      </div>
    </div>
  );
};

export default Pricing;
