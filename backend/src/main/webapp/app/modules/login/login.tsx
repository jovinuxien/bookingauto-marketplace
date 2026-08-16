import React, { useEffect, useState } from 'react';
import { useNavigate } from 'react-router-dom';

import { useAppDispatch, useAppSelector } from 'app/config/store';
import { login } from 'app/shared/reducers/auth.reducer';

const Login = () => {
  const dispatch = useAppDispatch();
  const navigate = useNavigate();
  const { session, loading, error } = useAppSelector(state => state.auth);

  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');

  useEffect(() => {
    if (session) {
      navigate('/konsol', { replace: true });
    }
  }, [session, navigate]);

  const submit = (event: React.FormEvent) => {
    event.preventDefault();
    dispatch(login({ email, password }));
  };

  return (
    <div className="row justify-content-center">
      <div className="col-md-5">
        <h1 className="h4 mb-3">Logga in</h1>
        <p className="text-muted small">För dig som driver en salong.</p>

        {error && <div className="alert alert-warning">{error}</div>}

        <form onSubmit={submit}>
          <div className="mb-3">
            <label className="form-label" htmlFor="email">E-post</label>
            <input id="email" type="email" className="form-control" autoComplete="username"
              required value={email} onChange={event => setEmail(event.target.value)} />
          </div>
          <div className="mb-3">
            <label className="form-label" htmlFor="password">Lösenord</label>
            <input id="password" type="password" className="form-control"
              autoComplete="current-password" required value={password}
              onChange={event => setPassword(event.target.value)} />
          </div>
          <button className="btn btn-primary" type="submit" disabled={loading}>
            {loading ? 'Loggar in…' : 'Logga in'}
          </button>
        </form>
      </div>
    </div>
  );
};

export default Login;
