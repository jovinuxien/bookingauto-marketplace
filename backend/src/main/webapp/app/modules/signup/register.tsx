import React, { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';

import { useAppDispatch, useAppSelector } from 'app/config/store';
import { register } from 'app/shared/reducers/signup.reducer';
import axios from 'app/config/axiosinstance';
import type { CategoryChoice, Registration } from 'app/shared/model/signup.model';

const EMPTY: Registration = {
  salonName: '',
  email: '',
  password: '',
  addressLine: '',
  postalCode: '',
  city: '',
  category: '',
};

/**
 * Registering a salon.
 *
 * The confirmation screen is careful about what it claims. It says an email is
 * on its way, and never that the address was new — the server deliberately does
 * not tell this page which, because a form that reacted differently to a known
 * address would be a way to ask which salons are on the platform.
 */
const Register = () => {
  const dispatch = useAppDispatch();
  const { submitting, submitted, problems, error } = useAppSelector(state => state.signup);
  const [form, setForm] = useState<Registration>(EMPTY);
  // Fetched rather than written here: the server validates the choice
  // against the same table, and a list copied into the frontend would be
  // one more place for it to drift from. Empty until it arrives, and the
  // select stays usable with no options so a slow answer is a slow answer
  // rather than a broken form.
  const [choices, setChoices] = useState<CategoryChoice[]>([]);

  useEffect(() => {
    axios.get<CategoryChoice[]>('/categories').then(
      response => setChoices(response.data),
      () => setChoices([]),
    );
  }, []);

  const set = (field: keyof Registration) =>
    (event: React.ChangeEvent<HTMLInputElement | HTMLSelectElement>) =>
    setForm({ ...form, [field]: event.target.value });

  const submit = (event: React.FormEvent) => {
    event.preventDefault();
    dispatch(register(form));
  };

  if (submitted) {
    return (
      <div className="row justify-content-center">
        <div className="col-md-6">
          <h1 className="h4 mb-3">Kolla din e-post</h1>
          <p>
            Om allt stämmer har vi skickat en länk till <strong>{form.email}</strong>. Klicka på
            den för att slutföra registreringen.
          </p>
          <p className="text-muted small">
            Länken gäller i 24 timmar. Ingenting skapas förrän du klickat på den.
          </p>
        </div>
      </div>
    );
  }

  return (
    <div className="row justify-content-center">
      <div className="col-md-6">
        <h1 className="h4 mb-3">Registrera din salong</h1>
        <p className="text-muted small">
          Du behöver inte betala något för att komma igång. Vi tar en provision på varje bokning
          som görs via oss.
        </p>

        {error && <div className="alert alert-warning">{error}</div>}

        <form onSubmit={submit} noValidate>
          <Field id="salonName" label="Salongens namn" value={form.salonName}
            onChange={set('salonName')} problem={problems.salonName} autoComplete="organization" />

          <div className="mb-3">
            <label className="form-label" htmlFor="category">Vad erbjuder ni?</label>
            <select
              id="category"
              className={problems.category ? 'form-select is-invalid' : 'form-select'}
              value={form.category}
              onChange={set('category')}
            >
              <option value="">Välj…</option>
              {choices.map(choice => (
                <option key={choice.slug} value={choice.slug}>{choice.label}</option>
              ))}
            </select>
            {problems.category
              ? <div className="invalid-feedback">{problems.category}</div>
              : <div className="form-text">Tjänster vi inte känner igen på namnet hamnar här.</div>}
          </div>
          <Field id="addressLine" label="Gatuadress" value={form.addressLine}
            onChange={set('addressLine')} problem={problems.addressLine}
            autoComplete="street-address" />

          <div className="row">
            <div className="col-5">
              <Field id="postalCode" label="Postnummer" value={form.postalCode}
                onChange={set('postalCode')} problem={problems.postalCode}
                autoComplete="postal-code" />
            </div>
            <div className="col-7">
              <Field id="city" label="Ort" value={form.city} onChange={set('city')}
                problem={problems.city} autoComplete="address-level2" />
            </div>
          </div>

          <Field id="email" label="E-post" type="email" value={form.email} onChange={set('email')}
            problem={problems.email} autoComplete="email" />

          <Field id="password" label="Lösenord" type="password" value={form.password}
            onChange={set('password')} problem={problems.password} autoComplete="new-password"
            hint="Minst 10 tecken." />

          <button className="btn btn-primary" type="submit" disabled={submitting}>
            {submitting ? 'Skickar…' : 'Registrera'}
          </button>
        </form>

        <p className="text-muted small mt-4">
          Har du redan ett konto? <Link to="/logga-in">Logga in</Link>.
        </p>
      </div>
    </div>
  );
};

interface FieldProps {
  id: keyof Registration;
  label: string;
  value: string;
  onChange: (event: React.ChangeEvent<HTMLInputElement>) => void;
  problem?: string;
  type?: string;
  autoComplete?: string;
  hint?: string;
}

/**
 * One input and whatever the server said about it.
 *
 * The message is rendered against the field rather than collected into a
 * summary at the top, because a person fixing a postal code should not have to
 * work out which of six inputs a sentence is about. `noValidate` on the form is
 * what lets the server be the one authority on what is acceptable — two
 * validators disagreeing is how a field becomes impossible to fill in.
 */
const Field = ({ id, label, value, onChange, problem, type, autoComplete, hint }: FieldProps) => (
  <div className="mb-3">
    <label className="form-label" htmlFor={id}>{label}</label>
    <input
      id={id}
      type={type ?? 'text'}
      className={problem ? 'form-control is-invalid' : 'form-control'}
      autoComplete={autoComplete}
      value={value}
      onChange={onChange}
    />
    {problem && <div className="invalid-feedback">{problem}</div>}
    {!problem && hint && <div className="form-text">{hint}</div>}
  </div>
);

export default Register;
