import React, { useEffect } from 'react';
import { Navigate, useLocation } from 'react-router-dom';

import { useAppDispatch, useAppSelector } from 'app/config/store';
import { loadSession } from 'app/shared/reducers/auth.reducer';

/**
 * Guards the console.
 *
 * <p>The real enforcement is on the server — every console endpoint is scoped
 * to the session's provider — and this only decides what to render. Saying so
 * matters: a route guard that people mistake for access control is how a client
 * check ends up being the only check.
 */
const PrivateRoute = ({ children }: { children: React.ReactNode }) => {
  const dispatch = useAppDispatch();
  const location = useLocation();
  const { session, resolved } = useAppSelector(state => state.auth);

  useEffect(() => {
    if (!resolved) {
      dispatch(loadSession());
    }
  }, [dispatch, resolved]);

  // Waiting, not denied. Redirecting before the session has been checked logs
  // people out every time they reload.
  if (!resolved) {
    return <p className="text-muted">Laddar…</p>;
  }

  if (!session) {
    return <Navigate to="/logga-in" state={{ from: location.pathname }} replace />;
  }

  return <>{children}</>;
};

export default PrivateRoute;
